import Foundation

// The helper's logic, checked without a window or a network: scripts/check.sh. Exit code 0 means all passed.
var failed = 0
var passed = 0
func check(_ ok: Bool, _ what: String) {
    if ok { passed += 1 } else { failed += 1; print("FAIL: \(what)") }
}

final class FakeHost: HelperHost {
    var computerName = "Halcyons-Air"
    var allowAnswer = true
    var asked: [String] = []
    var pins: [(String, String, Int, String?)] = []
    var pinAnswer: String? = nil
    var setups: [String] = []
    let book: PhoneBook
    init(book: PhoneBook) { self.book = book }
    func allow(phone: String) -> Bool { asked.append(phone); return allowAnswer }
    func knows(token: String) -> Bool { book.knows(token) }
    func addPhone(_ name: String) -> String { book.add(name) }
    func typePin(_ pin: String, name: String, port: Int, phoneAddress: String?) -> String? {
        pins.append((pin, name, port, phoneAddress)); return pinAnswer
    }
    func showSetup(for phone: String) { setups.append(phone) }
}

// The find.
check(Wire.isFind(Array("POCKETXR-FIND 1".utf8)), "the phone's find")
check(!Wire.isFind(Array("HELLO".utf8)) && !Wire.isFind([]), "anything else is not")
check(Wire.here(name: "Halcyons-Air") == "POCKETXR-HERE 1 47610 Halcyons-Air", "the answer: \(Wire.here(name: "Halcyons-Air"))")
check(Wire.here(name: "a\nb") == "POCKETXR-HERE 1 47610 a b", "a name stays on one line")

// Lines.
check(Wire.parse(#"{"op":"pair","phone":"Mike's Z Flip7"}"#)?["phone"] as? String == "Mike's Z Flip7", "a request line")
check(Wire.parse("not json") == nil && Wire.parse("") == nil && Wire.parse("[1]") == nil, "not a request")
check(Wire.encode(["ok": true, "token": "t"]) == #"{"ok":true,"token":"t"}"#, "an answer: \(Wire.encode(["ok": true, "token": "t"]))")
check(Wire.encode(["ok": false]) == #"{"ok":false}"#, "false is false, not 0")
check(Wire.port(48989) == 48989 && Wire.port("49989") == 49989 && Wire.port(nil) == 47989 && Wire.port("x") == 47989 && Wire.port(0) == 47989, "ports")

// Answers.
let dir = FileManager.default.temporaryDirectory.appendingPathComponent("pchelper-checks-\(UUID().uuidString)")
try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
let book = PhoneBook(url: dir.appendingPathComponent("phones.json"))
let host = FakeHost(book: book)
var a = Requests.answer(["op": "status", "token": "nope"], from: "10.0.0.2", host: host)
check(a["ok"] as? Bool == false && a["why"] as? String == "not paired", "an unknown phone: \(a)")
host.allowAnswer = false
a = Requests.answer(["op": "pair", "phone": "Flip"], from: "10.0.0.2", host: host)
check(a["ok"] as? Bool == false && a["why"] as? String == "not allowed on the PC" && host.asked == ["Flip"], "not allowed: \(a)")
host.allowAnswer = true
a = Requests.answer(["op": "pair"], from: "10.0.0.2", host: host)
let token = a["token"] as? String ?? ""
check(a["ok"] as? Bool == true && token.count == 32 && a["pc"] as? String == "Halcyons-Air" && host.asked.last == "A phone", "allowed: \(a)")
a = Requests.answer(["op": "status", "token": token], from: "10.0.0.2", host: host)
check(a["ok"] as? Bool == true && a["steam"] as? Bool == false && a["steamvr"] as? String == "off" && a["pc"] as? String == "Halcyons-Air", "status: \(a)")
a = Requests.answer(["op": "apollo-pin", "token": token, "pin": "1234", "name": "Mike's Z Flip7", "port": 47989], from: "10.0.0.2", host: host)
check(a["ok"] as? Bool == true && host.pins.last! == ("1234", "Mike's Z Flip7", 47989, "10.0.0.2"), "the PIN goes in, with the phone's address: \(a)")
host.pinAnswer = "Sunshine did not take the PIN"
a = Requests.answer(["op": "apollo-pin", "token": token, "pin": "1234", "virtual": true], from: "10.0.0.2", host: host)
check(a["ok"] as? Bool == false && a["why"] as? String == "Sunshine did not take the PIN" && host.pins.last!.1 == "Pocket XR", "why it did not: \(a)")
a = Requests.answer(["op": "apollo-pin", "token": token], from: "10.0.0.2", host: host)
check(a["why"] as? String == "no PIN", "no PIN: \(a)")
a = Requests.answer(["op": "screens-setup", "token": token, "phone": "Flip"], from: "10.0.0.2", host: host)
check(a["ok"] as? Bool == true && host.setups == ["Flip"], "the setup opens on the Mac: \(a)")
a = Requests.answer(["op": "dance", "token": token], from: "10.0.0.2", host: host)
check(a["why"] as? String == "unknown request", "an unknown request")

// The phone book: the Linux helper's file format, kept between runs, forgotten on request.
check(PhoneBook(url: book.url).knows(token) && !book.knows("") && !book.knows("x"), "a phone is remembered")
let perms = (try? FileManager.default.attributesOfItem(atPath: book.url.path)[.posixPermissions] as? Int) ?? 0
check(perms == 0o600, "only this user can read it: \(String(perms, radix: 8))")
let legacy = dir.appendingPathComponent("pc-helper.json")
try? Data(#"{"phones": [{"name": "Mike's Z Flip7", "token": "abc"}, {"name": "bad"}]}"#.utf8).write(to: legacy)
let fresh = PhoneBook(url: dir.appendingPathComponent("new/phones.json"))
check(fresh.importPhones(from: legacy) && fresh.knows("abc") && fresh.phones().count == 1, "the script helper's phones are taken")
check(!fresh.importPhones(from: legacy), "only once, into an empty book")
fresh.forgetAll()
check(fresh.phones().isEmpty && !fresh.knows("abc"), "forgotten")
check(PhoneBook.newToken() != PhoneBook.newToken() && PhoneBook.newToken().allSatisfy(\.isHexDigit), "tokens are new each time")

// Sunshine's pairing list, as Sunshine 2026.914 answered GET /api/pin.
let listed = try! JSONSerialization.jsonObject(with: Data(#"{"pairings":[{"address":"192.168.1.17","id":"2947ce7fbdd78880d49964a0a64990d2","name":"roth"}]}"#.utf8))
let waiting = SunshinePairing.waiting(in: listed)!
check(waiting == [.init(id: "2947ce7fbdd78880d49964a0a64990d2", address: "192.168.1.17", name: "roth")], "the waiting list")
check(SunshinePairing.pick(waiting, phoneAddress: "192.168.1.17") == "2947ce7fbdd78880d49964a0a64990d2", "the asking phone's own")
check(SunshinePairing.pick(waiting, phoneAddress: "10.0.0.9") == "2947ce7fbdd78880d49964a0a64990d2", "the only one waiting")
let two = waiting + [.init(id: "b", address: "192.168.1.20", name: "x")]
check(SunshinePairing.pick(two, phoneAddress: "192.168.1.20") == "b" && SunshinePairing.pick(two, phoneAddress: "10.0.0.9") == nil, "two waiting: only its own")
check(SunshinePairing.pick([], phoneAddress: "1.2.3.4") == nil, "none waiting")
check(SunshinePairing.waiting(in: ["error": "x"]) == nil && SunshinePairing.waiting(in: nil) == nil, "an older Sunshine has no list")
check(SunshinePairing.waiting(in: ["pairings": NSNull()]) == [], "an empty list")
let body = SunshinePairing.body(pairingId: "ab", pin: "1234", name: "Flip")
check(body["pairing_id"] as? String == "ab" && body["pin"] as? String == "1234" && SunshinePairing.body(pairingId: nil, pin: "1", name: "n")["pairing_id"] == nil, "the PIN's body")
check(SunshinePairing.accepted(["status": true]) && SunshinePairing.accepted(["status": "true"]) && !SunshinePairing.accepted(["status": false]) && !SunshinePairing.accepted(nil), "accepted")

// A phone's earlier pairings, as GET /api/clients/list answers.
let clients = try! JSONSerialization.jsonObject(with: Data(#"{"named_certs":[{"name":"Mike's Z Flip7","uuid":"7FFC","enabled":true},{"name":"Den","uuid":"AAAA","enabled":true},{"name":"Mike's Z Flip7","uuid":"41F7","enabled":true}],"status":true}"#.utf8))
check(SunshineClients.uuids(named: "Mike's Z Flip7", in: clients) == ["7FFC", "41F7"], "the phone's earlier pairings, not others'")
check(SunshineClients.uuids(named: "Nobody", in: clients).isEmpty && SunshineClients.uuids(named: "x", in: nil).isEmpty, "none")

// Sunshine's state and release.
check(SunshineState.hasLogin(Data(#"{"username":"pocketxr","salt":"s","password":"p"}"#.utf8)), "a login")
check(!SunshineState.hasLogin(Data(#"{"root":{}}"#.utf8)) && !SunshineState.hasLogin(nil) && !SunshineState.hasLogin(Data("x".utf8)), "no login")
let release = Data(#"{"tag_name":"v2026.914.233613","assets":[{"name":"Sunshine-macOS-arm64.dmg","browser_download_url":"https://github.com/SunshineStream/Sunshine/releases/download/v2026.914.233613/Sunshine-macOS-arm64.dmg"},{"name":"Sunshine-macOS-x86_64.dmg","browser_download_url":"https://github.com/SunshineStream/Sunshine/releases/download/v2026.914.233613/Sunshine-macOS-x86_64.dmg"}]}"#.utf8)
check(SunshineRelease.dmgURL(in: release, arch: "x86_64")?.url.lastPathComponent == "Sunshine-macOS-x86_64.dmg", "Intel's installer")
check(SunshineRelease.dmgURL(in: release, arch: "arm64")?.url.lastPathComponent == "Sunshine-macOS-arm64.dmg" && SunshineRelease.dmgURL(in: release, arch: "arm64")?.version == "v2026.914.233613", "Apple silicon's")
check(SunshineRelease.dmgURL(in: release, arch: "ppc") == nil && SunshineRelease.dmgURL(in: Data("{}".utf8), arch: "arm64") == nil, "none")
let http = Data(#"{"assets":[{"name":"Sunshine-macOS-arm64.dmg","browser_download_url":"http://example.com/x.dmg"}]}"#.utf8)
check(SunshineRelease.dmgURL(in: http, arch: "arm64") == nil, "only https")

try? FileManager.default.removeItem(at: dir)
print(failed == 0 ? "helper checks: all \(passed) passed" : "helper checks: \(failed) failed, \(passed) passed")
exit(failed == 0 ? 0 : 1)
