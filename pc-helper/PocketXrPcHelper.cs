// Pocket XR PC helper (private; docs/PCVR-PLAN.md step 6).
//
// A small tray program on the gaming PC. Pocket XR's PC VR tile finds it on the local network and asks it to get the PC
// ready and start SteamVR, so nobody has to touch the PC:
//   - checks that Steam, SteamVR and ALVR's streamer are installed, and says what is missing;
//   - trusts the phone in ALVR and sets the view and frame rate the glasses need;
//   - starts SteamVR, and tells the phone when it is ready.
//
// Protocol (port 47610, UDP and TCP, local network only):
//   UDP  phone broadcasts "POCKETXR-FIND 1"; the helper answers "POCKETXR-HERE 1 <tcp port> <PC name>".
//   TCP  one JSON line in, one JSON line out:
//        {"op":"pair","phone":"<name>"}                    asks the PC's user; {"ok":true,"token":"..."} or {"ok":false,...}
//        {"op":"status"|"start","token":"...","alvr":"<the phone's ALVR name>"}
//            {"ok":true,"pc":"...","steam":bool,"alvr":bool,"steamvr":"off|starting|ready"}; start also gets the PC ready
//        {"op":"idr","token":"..."}                        asks the streamer for a fresh full frame
// A phone is allowed once on the PC (pair); its token is enough after that. Tokens live in
// %APPDATA%\PocketXR\pc-helper.json; "Forget phones" in the tray menu clears them.
//
// Installs itself: run from anywhere, it copies itself to %LOCALAPPDATA%\PocketXR\PcHelper and starts with Windows.
//
// Build (Windows' own compiler, nothing to install):
//   csc /target:winexe /win32icon:pocketxr.ico /r:System.Web.Extensions.dll /r:System.Windows.Forms.dll
//       /r:System.Drawing.dll /out:PocketXrPcHelper.exe PocketXrPcHelper.cs

using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Win32;

namespace PocketXr
{
    static class Program
    {
        [STAThread]
        static void Main(string[] args)
        {
            bool created;
            using (var mutex = new Mutex(true, "Global\\PocketXrPcHelper", out created))
            {
                if (!created) return;
                if (Install.Here(args)) return;
                Application.EnableVisualStyles();
                Application.Run(new Tray());
            }
        }
    }

    /// <summary>Copies the helper to its own folder and starts it with Windows. True when the copy took over.</summary>
    static class Install
    {
        public static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PocketXR", "PcHelper");
        public static readonly string Exe = Path.Combine(Dir, "PocketXrPcHelper.exe");
        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        const string RunName = "Pocket XR PC helper";

        public static bool Here(string[] args)
        {
            string self = Application.ExecutablePath;
            if (string.Equals(Path.GetFullPath(self), Path.GetFullPath(Exe), StringComparison.OrdinalIgnoreCase)) return false;
            try
            {
                Directory.CreateDirectory(Dir);
                File.Copy(self, Exe, true);
                SetStartWithWindows(true);
                // The PowerShell version's shortcut, from before.
                string oldLink = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Startup), "Pocket XR PC helper.lnk");
                if (File.Exists(oldLink)) File.Delete(oldLink);
                Process.Start(new ProcessStartInfo(Exe) { UseShellExecute = true, Arguments = "--installed" });
                return true;
            }
            catch (Exception e)
            {
                Log.Line("install: " + e.Message);
                return false;
            }
        }

        public static bool StartsWithWindows
        {
            get { using (var k = Registry.CurrentUser.OpenSubKey(RunKey)) return k != null && k.GetValue(RunName) != null; }
        }

        public static void SetStartWithWindows(bool on)
        {
            using (var k = Registry.CurrentUser.CreateSubKey(RunKey))
            {
                if (on) k.SetValue(RunName, "\"" + Exe + "\"");
                else if (k.GetValue(RunName) != null) k.DeleteValue(RunName);
            }
        }
    }

    static class Log
    {
        static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PocketXR");
        static readonly object Gate = new object();

        public static void Line(string text)
        {
            lock (Gate)
            {
                try
                {
                    Directory.CreateDirectory(Dir);
                    File.AppendAllText(Path.Combine(Dir, "pc-helper.log"), DateTime.Now.ToString("HH:mm:ss ") + text + Environment.NewLine);
                }
                catch { }
            }
        }
    }

    /// <summary>Steam, SteamVR and ALVR's streamer on this PC.</summary>
    static class Pc
    {
        static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = int.MaxValue, RecursionLimit = 200 };
        static DateTime startedAt = DateTime.MinValue;
        static readonly HashSet<string> readyFor = new HashSet<string>();

        public static string SteamExe
        {
            get
            {
                using (var k = Registry.CurrentUser.OpenSubKey(@"Software\Valve\Steam"))
                {
                    var p = k == null ? null : k.GetValue("SteamExe") as string;
                    return p != null && File.Exists(p) ? p : null;
                }
            }
        }

        /// <summary>ALVR's streamer folder: the SteamVR driver named alvr_server in openvrpaths.vrpath.</summary>
        public static string AlvrDir
        {
            get
            {
                try
                {
                    string paths = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "openvr", "openvrpaths.vrpath");
                    if (!File.Exists(paths)) return null;
                    var root = (Dictionary<string, object>)Json.DeserializeObject(File.ReadAllText(paths));
                    object drivers;
                    if (!root.TryGetValue("external_drivers", out drivers) || drivers == null) return null;
                    foreach (var d in (IEnumerable)drivers)
                    {
                        string dir = d as string;
                        if (dir == null) continue;
                        string manifest = Path.Combine(dir, "driver.vrdrivermanifest");
                        if (File.Exists(manifest) && File.ReadAllText(manifest).Contains("alvr_server")) return dir;
                    }
                }
                catch (Exception e) { Log.Line("drivers: " + e.Message); }
                return null;
            }
        }

        public static bool SteamVrRunning { get { return Process.GetProcessesByName("vrserver").Length > 0; } }

        /// <summary>off: SteamVR isn't running. starting: it is, but ALVR doesn't answer yet. ready: ALVR answers.</summary>
        public static string SteamVrState
        {
            get
            {
                if (!SteamVrRunning) return (DateTime.Now - startedAt).TotalSeconds < 30 ? "starting" : "off";
                return Api("GET", "/api/version", null) != null ? "ready" : "starting";
            }
        }

        /// <summary>ALVR's own web API on this PC, while SteamVR runs. Null if it doesn't answer.</summary>
        public static string Api(string method, string path, string body)
        {
            try
            {
                var r = (HttpWebRequest)WebRequest.Create("http://127.0.0.1:8082" + path);
                r.Method = method;
                r.Timeout = 1500;
                r.Headers.Add("X-ALVR", "true");
                if (body != null)
                {
                    r.ContentType = "application/json";
                    var bytes = Encoding.UTF8.GetBytes(body);
                    using (var s = r.GetRequestStream()) s.Write(bytes, 0, bytes.Length);
                }
                using (var resp = (HttpWebResponse)r.GetResponse())
                using (var reader = new StreamReader(resp.GetResponseStream())) return reader.ReadToEnd();
            }
            catch { return null; }
        }

        /// <summary>
        /// What the glasses need from ALVR: the phone trusted, and the view sized by the phone (it asks for the glasses'
        /// own size, a little wider), at 60 frames a second. Returns true if a setting changed that only applies after
        /// SteamVR restarts.
        /// </summary>
        public static bool Prepare(string phoneAlvrName)
        {
            if (SteamVrRunning)
            {
                // ALVR's API takes changes but gives the session back empty (21.0.0-dev14): read its file, which it keeps
                // up to date while running.
                string dirNow = AlvrDir;
                string sessionFile = dirNow == null ? null : Path.Combine(dirNow, "session.json");
                if (sessionFile == null || !File.Exists(sessionFile) || Api("GET", "/api/version", null) == null) return false;
                string session = File.ReadAllText(sessionFile);
                if (!string.IsNullOrEmpty(phoneAlvrName) && !readyFor.Contains(phoneAlvrName))
                {
                    TrustOverApi(phoneAlvrName);
                    readyFor.Add(phoneAlvrName);
                }
                var root = (Dictionary<string, object>)Json.DeserializeObject(session);
                var settings = (Dictionary<string, object>)root["session_settings"];
                var video = (Dictionary<string, object>)settings["video"];
                if (HandGesturesOn(settings))
                {
                    Log.Line("ALVR: turning its own hand gestures off over its API (Pocket XR sends the pinch)");
                    Api("POST", "/api/session/values", Json.Serialize(new object[] {
                        PathValue(new object[] { "headset", "controllers", "content", "hand_tracking_interaction", "enabled" }, false),
                    }));
                }
                if (VideoIsRight(video)) return false;
                Log.Line("ALVR: setting the glasses' view and frame rate over its API");
                Api("POST", "/api/session/values", Json.Serialize(new object[] {
                    PathValue(new object[] { "video", "transcoding_view_resolution", "variant" }, "Scale"),
                    PathValue(new object[] { "video", "transcoding_view_resolution", "Scale" }, 1.0),
                    PathValue(new object[] { "video", "emulated_headset_view_resolution", "variant" }, "Scale"),
                    PathValue(new object[] { "video", "emulated_headset_view_resolution", "Scale" }, 1.0),
                    PathValue(new object[] { "video", "preferred_fps" }, 60.0),
                }));
                return true;
            }
            string dir = AlvrDir;
            if (dir == null) return false;
            string file = Path.Combine(dir, "session.json");
            if (!File.Exists(file)) return false;
            try
            {
                var root = (Dictionary<string, object>)Json.DeserializeObject(File.ReadAllText(file));
                bool changed = false;
                var settings = (Dictionary<string, object>)root["session_settings"];
                var video = (Dictionary<string, object>)settings["video"];
                if (HandGesturesOn(settings))
                {
                    HandGestures(settings)["enabled"] = false;
                    changed = true;
                }
                if (!VideoIsRight(video))
                {
                    foreach (var key in new[] { "transcoding_view_resolution", "emulated_headset_view_resolution" })
                    {
                        var res = (Dictionary<string, object>)video[key];
                        res["variant"] = "Scale";
                        res["Scale"] = 1.0;
                    }
                    video["preferred_fps"] = 60.0;
                    changed = true;
                }
                if (!string.IsNullOrEmpty(phoneAlvrName))
                {
                    object listObj;
                    root.TryGetValue("client_connections", out listObj);
                    var list = listObj as Dictionary<string, object> ?? new Dictionary<string, object>();
                    object entryObj;
                    var entry = list.TryGetValue(phoneAlvrName, out entryObj) ? (Dictionary<string, object>)entryObj : null;
                    if (entry == null || !(entry["trusted"] is bool && (bool)entry["trusted"]))
                    {
                        list[phoneAlvrName] = new Dictionary<string, object> {
                            { "display_name", "Pocket XR" }, { "current_ip", null }, { "manual_ips", new object[0] },
                            { "trusted", true }, { "connection_state", "Disconnected" },
                        };
                        root["client_connections"] = list;
                        changed = true;
                    }
                }
                if (changed)
                {
                    File.Copy(file, file + ".before-pocketxr", true);
                    File.WriteAllText(file, Json.Serialize(root));
                    Log.Line("ALVR: trusted the phone and set the glasses' view in its settings file");
                }
            }
            catch (Exception e) { Log.Line("ALVR settings: " + e.Message); }
            return false;
        }

        /**
         * ALVR's own hand gestures, off: Pocket XR sends the tracked hands' pinch as the trigger itself, the same pinch as
         * in its Space; ALVR's guess from finger distances did nothing with the glasses' hands (8 November 2026).
         */
        static Dictionary<string, object> HandGestures(Dictionary<string, object> settings)
        {
            var controllers = (Dictionary<string, object>)((Dictionary<string, object>)settings["headset"])["controllers"];
            var content = (Dictionary<string, object>)controllers["content"];
            return (Dictionary<string, object>)content["hand_tracking_interaction"];
        }

        static bool HandGesturesOn(Dictionary<string, object> settings)
        {
            try { var h = HandGestures(settings); return h["enabled"] is bool && (bool)h["enabled"]; }
            catch { return false; }
        }

        static bool VideoIsRight(Dictionary<string, object> video)
        {
            foreach (var key in new[] { "transcoding_view_resolution", "emulated_headset_view_resolution" })
            {
                var res = (Dictionary<string, object>)video[key];
                if ((string)res["variant"] != "Scale" || Math.Abs(Convert.ToDouble(res["Scale"]) - 1.0) > 1e-6) return false;
            }
            return Math.Abs(Convert.ToDouble(video["preferred_fps"]) - 60.0) < 0.5;
        }

        static Dictionary<string, object> PathValue(object[] path, object value)
        {
            var segments = path.Select(p => (object)new Dictionary<string, object> { { "Name", p } }).ToList();
            segments.Insert(0, new Dictionary<string, object> { { "Name", "session_settings" } });
            return new Dictionary<string, object> { { "path", segments }, { "value", value } };
        }

        static void TrustOverApi(string host)
        {
            Api("POST", "/api/session/client-connections", Json.Serialize(new object[] { host,
                new Dictionary<string, object> { { "AddIfMissing", new Dictionary<string, object> { { "trusted", true }, { "manual_ips", new object[0] } } } } }));
            Api("POST", "/api/session/client-connections", Json.Serialize(new object[] { host, "Trust" }));
            Api("POST", "/api/session/client-connections", Json.Serialize(new object[] { host,
                new Dictionary<string, object> { { "SetDisplayName", "Pocket XR" } } }));
            Log.Line("ALVR: trusted " + host + " over its API");
        }

        /**
         * ALVR's dashboard, which ALVR needs running beside SteamVR: when a setting needs SteamVR restarted, ALVR asks
         * the dashboard to do it, and without one it waits forever with no picture (8 November 2026). Started minimised.
         */
        public static void StartAlvrDashboard()
        {
            if (Process.GetProcessesByName("ALVR Dashboard").Length > 0) return;
            string dir = AlvrDir;
            if (dir == null) return;
            string exe = Path.Combine(dir, "ALVR Dashboard.exe");
            if (!File.Exists(exe)) return;
            Log.Line("starting ALVR's dashboard");
            Process.Start(new ProcessStartInfo(exe) { UseShellExecute = true, WorkingDirectory = dir, WindowStyle = ProcessWindowStyle.Minimized });
        }

        /**
         * SteamVR blocks a driver after it crashed or was stopped abruptly a few times ("blocked by a previous safe mode
         * event"), and then says no headset is found. With SteamVR closed, ALVR's block is lifted.
         */
        static void UnblockAlvr()
        {
            try
            {
                string steam = SteamExe == null ? null : Path.GetDirectoryName(SteamExe);
                if (steam == null) return;
                string file = Path.Combine(steam, "config", "steamvr.vrsettings");
                if (!File.Exists(file)) return;
                var root = (Dictionary<string, object>)Json.DeserializeObject(File.ReadAllText(file));
                object driver;
                if (!root.TryGetValue("driver_alvr_server", out driver)) return;
                var d = driver as Dictionary<string, object>;
                if (d == null || !d.ContainsKey("blocked_by_safe_mode")) return;
                d.Remove("blocked_by_safe_mode");
                if (d.Count == 0) root.Remove("driver_alvr_server");
                File.Copy(file, file + ".before-pocketxr", true);
                File.WriteAllText(file, Json.Serialize(root));
                Log.Line("SteamVR had blocked ALVR (safe mode): unblocked");
            }
            catch (Exception e) { Log.Line("unblock: " + e.Message); }
        }

        public static void StartSteamVr()
        {
            if (SteamVrRunning || (DateTime.Now - startedAt).TotalSeconds < 30) return;
            startedAt = DateTime.Now;
            UnblockAlvr();
            StartAlvrDashboard();
            Log.Line("starting SteamVR for the phone");
            Process.Start(new ProcessStartInfo("steam://rungameid/250820") { UseShellExecute = true });
        }

        /** ALVR's dashboard restarts SteamVR when a setting needs it; it only has to be running. */
        public static void RestartSteamVr()
        {
            Log.Line("ALVR's view changed: its dashboard restarts SteamVR");
            startedAt = DateTime.Now;
            StartAlvrDashboard();
            Api("POST", "/api/steamvr/restart", "null");
        }
    }

    /// <summary>The paired phones' tokens.</summary>
    static class Phones
    {
        static readonly string File_ = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PocketXR", "pc-helper.json");
        static readonly JavaScriptSerializer Json = new JavaScriptSerializer();
        static readonly object Gate = new object();

        static List<Dictionary<string, object>> Load()
        {
            try
            {
                if (!File.Exists(File_)) return new List<Dictionary<string, object>>();
                var root = (Dictionary<string, object>)Json.DeserializeObject(File.ReadAllText(File_));
                object phones;
                if (!root.TryGetValue("phones", out phones) || phones == null) return new List<Dictionary<string, object>>();
                return ((IEnumerable)phones).Cast<Dictionary<string, object>>().ToList();
            }
            catch { return new List<Dictionary<string, object>>(); }
        }

        public static bool Known(string token)
        {
            if (string.IsNullOrEmpty(token)) return false;
            lock (Gate) return Load().Any(p => (p["token"] as string) == token);
        }

        public static string Add(string name)
        {
            lock (Gate)
            {
                var list = Load();
                string token = Guid.NewGuid().ToString("N");
                list.Add(new Dictionary<string, object> { { "name", name }, { "token", token } });
                Directory.CreateDirectory(Path.GetDirectoryName(File_));
                File.WriteAllText(File_, Json.Serialize(new Dictionary<string, object> { { "phones", list } }));
                return token;
            }
        }

        public static void Forget() { lock (Gate) if (File.Exists(File_)) File.Delete(File_); }
    }

    class Tray : ApplicationContext
    {
        const int Port = 47610;
        const string Name = "Pocket XR PC helper";
        readonly NotifyIcon icon = new NotifyIcon();
        readonly ToolStripMenuItem state = new ToolStripMenuItem("SteamVR: checking") { Enabled = false };
        readonly ToolStripMenuItem startWithWindows = new ToolStripMenuItem("Start with Windows");
        readonly SynchronizationContext ui;
        readonly System.Windows.Forms.Timer timer = new System.Windows.Forms.Timer { Interval = 3000 };
        readonly object pairGate = new object();
        readonly JavaScriptSerializer json = new JavaScriptSerializer();
        volatile bool running = true;

        public Tray()
        {
            // A Form-less context still needs a synchronization context for the question box.
            var keep = new Control();
            keep.CreateControl();
            ui = SynchronizationContext.Current ?? new WindowsFormsSynchronizationContext();

            icon.Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath) ?? SystemIcons.Application;
            icon.Text = Name;
            var menu = new ContextMenuStrip();
            menu.Items.Add(new ToolStripMenuItem(Name) { Enabled = false });
            menu.Items.Add(state);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Start SteamVR now", null, (s, e) => Pc.StartSteamVr());
            startWithWindows.Checked = Install.StartsWithWindows;
            startWithWindows.Click += (s, e) => { Install.SetStartWithWindows(!Install.StartsWithWindows); startWithWindows.Checked = Install.StartsWithWindows; };
            menu.Items.Add(startWithWindows);
            menu.Items.Add("Forget phones", null, (s, e) => { Phones.Forget(); Balloon("Phones forgotten. Each will ask again next time."); });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Quit", null, (s, e) => { running = false; icon.Visible = false; ExitThread(); });
            icon.ContextMenuStrip = menu;
            icon.Visible = true;

            timer.Tick += (s, e) => UpdateState();
            timer.Start();
            UpdateState();

            new Thread(Discovery) { IsBackground = true, Name = "discovery" }.Start();
            new Thread(Requests) { IsBackground = true, Name = "requests" }.Start();
            Log.Line("listening on port " + Port);
            Balloon(Environment.GetCommandLineArgs().Contains("--installed")
                ? "Installed. It starts with Windows, and Pocket XR can now start SteamVR from your phone."
                : "Ready. Pocket XR can start SteamVR from your phone.");
        }

        void Balloon(string text) { icon.ShowBalloonTip(4000, Name, text, ToolTipIcon.Info); }

        void UpdateState()
        {
            string s;
            if (Pc.SteamExe == null) s = "Steam is not installed";
            else if (Pc.AlvrDir == null) s = "ALVR's streamer is not set up";
            else s = Pc.SteamVrRunning ? "SteamVR: running" : "SteamVR: off";
            state.Text = s;
        }

        void Discovery()
        {
            using (var udp = new UdpClient(Port))
            {
                while (running)
                {
                    try
                    {
                        var from = new IPEndPoint(IPAddress.Any, 0);
                        var data = udp.Receive(ref from);
                        if (Encoding.ASCII.GetString(data).StartsWith("POCKETXR-FIND"))
                        {
                            var reply = Encoding.UTF8.GetBytes("POCKETXR-HERE 1 " + Port + " " + Environment.MachineName);
                            udp.Send(reply, reply.Length, from);
                        }
                    }
                    catch (Exception e) { Log.Line("udp: " + e.Message); Thread.Sleep(500); }
                }
            }
        }

        void Requests()
        {
            var tcp = new TcpListener(IPAddress.Any, Port);
            tcp.Start();
            while (running)
            {
                TcpClient client;
                try { client = tcp.AcceptTcpClient(); }
                catch { continue; }
                ThreadPool.QueueUserWorkItem(_ => Serve(client));
            }
        }

        void Serve(TcpClient client)
        {
            using (client)
            {
                try
                {
                    client.ReceiveTimeout = 3000;
                    var stream = client.GetStream();
                    var reader = new StreamReader(stream);
                    var writer = new StreamWriter(stream) { AutoFlush = true };
                    var request = (Dictionary<string, object>)json.DeserializeObject(reader.ReadLine() ?? "{}");
                    writer.WriteLine(json.Serialize(Answer(request)));
                }
                catch (Exception e) { Log.Line("tcp: " + e.Message); }
            }
        }

        static string Text(Dictionary<string, object> r, string key) { object v; return r.TryGetValue(key, out v) ? v as string : null; }

        Dictionary<string, object> Answer(Dictionary<string, object> request)
        {
            string op = Text(request, "op");
            if (op == "pair") return Pair(Text(request, "phone") ?? "A phone");
            if (!Phones.Known(Text(request, "token"))) return new Dictionary<string, object> { { "ok", false }, { "why", "not paired" } };
            bool steam = Pc.SteamExe != null, alvr = Pc.AlvrDir != null;
            if (op == "idr")
            {
                Pc.Api("POST", "/api/insert-idr", "null");
                return new Dictionary<string, object> { { "ok", true } };
            }
            if ((op == "start" || op == "status") && steam && alvr && Pc.SteamVrRunning) Pc.StartAlvrDashboard();
            if (op == "start" && steam && alvr)
            {
                if (Pc.Prepare(Text(request, "alvr"))) Pc.RestartSteamVr();
                Pc.StartSteamVr();
            }
            else if (op == "status" && steam && alvr && Pc.SteamVrRunning)
            {
                Pc.Prepare(Text(request, "alvr"));
            }
            return new Dictionary<string, object> {
                { "ok", true }, { "pc", Environment.MachineName }, { "steam", steam }, { "alvr", alvr }, { "steamvr", Pc.SteamVrState },
            };
        }

        /// <summary>Asks the PC's user once, on the tray's thread; one question at a time.</summary>
        Dictionary<string, object> Pair(string phone)
        {
            lock (pairGate)
            {
                var done = new ManualResetEvent(false);
                bool allowed = false;
                ui.Post(_ =>
                {
                    using (var owner = new Form { TopMost = true, ShowInTaskbar = false })
                    {
                        var answer = MessageBox.Show(owner, phone + " wants to start SteamVR on this PC for Pocket XR.\n\nAllow it?",
                            Name, MessageBoxButtons.YesNo, MessageBoxIcon.Question);
                        allowed = answer == DialogResult.Yes;
                    }
                    done.Set();
                }, null);
                if (!done.WaitOne(TimeSpan.FromSeconds(60)) || !allowed)
                {
                    Log.Line("pairing not allowed for " + phone);
                    return new Dictionary<string, object> { { "ok", false }, { "why", "not allowed on the PC" } };
                }
                string token = Phones.Add(phone);
                Log.Line("paired " + phone);
                ui.Post(_ => Balloon(phone + " can now start SteamVR on this PC."), null);
                return new Dictionary<string, object> { { "ok", true }, { "token", token }, { "pc", Environment.MachineName } };
            }
        }
    }
}
