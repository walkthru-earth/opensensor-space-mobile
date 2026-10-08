import Foundation
import Security

/// Device-only bearer credentials and the identity established over encrypted BLE.
struct LanCredential: Codable {
    let station: String
    let device: String
    let peripheral: String
    let token: Data
    let port: Int
}

final class LanCredentials {
    private let service = "earth.walkthru.opensensor.lan"
    func save(_ value: LanCredential) throws {
        guard value.token.count == 32, (1...65535).contains(value.port) else { throw LanFailure.invalidReply }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: value.device.lowercased(),
        ]
        let attributes: [String: Any] = [
            kSecValueData as String: try JSONEncoder().encode(value),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let result = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if result == errSecItemNotFound {
            guard SecItemAdd(query.merging(attributes) { _, new in new } as CFDictionary, nil) == errSecSuccess else {
                throw LanFailure.credentialsUnavailable
            }
        } else if result != errSecSuccess {
            throw LanFailure.credentialsUnavailable
        }
    }
    func find(device: String) -> LanCredential? {
        var item: CFTypeRef?
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: device.lowercased(), kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data,
            let value = try? JSONDecoder().decode(LanCredential.self, from: data), value.token.count == 32,
            value.device.lowercased() == device.lowercased(), value.device.count == 12,
            value.device.allSatisfy({ $0.isHexDigit }), UUID(uuidString: value.peripheral) != nil,
            (1...65535).contains(value.port), !value.station.isEmpty,
            value.station.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") })
        else { return nil }
        return value
    }
    func remove(device: String) {
        SecItemDelete(
            [
                kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                kSecAttrAccount as String: device.lowercased(),
            ] as CFDictionary)
    }
}
