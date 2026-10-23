import Foundation
import CryptoKit

public enum MalodyError: LocalizedError {
    case invalid(String)
    public var errorDescription: String? { switch self { case .invalid(let text): return text } }
}

public enum Digests {
    public static func md5(_ data: Data) -> String { Insecure.MD5.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    public static func sha256(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
}

public enum WireValue {
    case integer(UInt64), real(Double), bytes(Data)
    public var number: Double? {
        switch self { case .integer(let n): return Double(n); case .real(let n): return n; case .bytes: return nil }
    }
}

/// Protobuf-compatible wire decoder. Motion events are skipped rather than copied.
public enum Wire {
    public static func decode(_ data: Data, wanted: Set<Int>? = nil) throws -> [Int: WireValue] {
        let bytes = [UInt8](data)
        var offset = 0
        func varint() throws -> UInt64 {
            var result: UInt64 = 0
            for index in 0..<10 {
                guard offset < bytes.count else { throw MalodyError.invalid("回放字段被截断") }
                let byte = bytes[offset]; offset += 1
                if index == 9 && byte > 1 { throw MalodyError.invalid("回放整数溢出") }
                result |= UInt64(byte & 127) << (index * 7)
                if byte < 128 { return result }
            }
            throw MalodyError.invalid("回放整数无效")
        }
        var result: [Int: WireValue] = [:]
        while offset < bytes.count {
            let tag = try varint(), number = Int(tag >> 3), wire = Int(tag & 7)
            guard number > 0 else { throw MalodyError.invalid("回放字段编号无效") }
            let keep = wanted == nil || wanted!.contains(number)
            switch wire {
            case 0:
                let n = try varint(); if keep { result[number] = .integer(n) }
            case 1, 5:
                let count = wire == 1 ? 8 : 4
                guard bytes.count - offset >= count else { throw MalodyError.invalid("回放数值被截断") }
                var bits: UInt64 = 0
                for i in 0..<count { bits |= UInt64(bytes[offset + i]) << (i * 8) }
                if keep { result[number] = .real(wire == 1 ? Double(bitPattern: bits) : Double(Float(bitPattern: UInt32(bits)))) }
                offset += count
            case 2:
                let length = try varint()
                guard length <= UInt64(bytes.count - offset) else { throw MalodyError.invalid("回放字段长度无效") }
                let count = Int(length)
                if keep { result[number] = .bytes(Data(bytes[offset..<(offset + count)])) }
                offset += count
            default: throw MalodyError.invalid("回放包含不支持的字段类型")
            }
        }
        return result
    }
    public static func text(_ fields: [Int: WireValue], _ key: Int) throws -> String {
        guard case .bytes(let data)? = fields[key], let text = String(data: data, encoding: .utf8) else {
            throw MalodyError.invalid("回放缺少文字字段 \(key)")
        }
        return text
    }
    public static func integer(_ fields: [Int: WireValue], _ key: Int) throws -> Int64 {
        guard case .integer(let n)? = fields[key], let value = Int64(exactly: n) else { throw MalodyError.invalid("回放缺少整数字段 \(key)") }
        return value
    }
}

public struct ReplayHeader {
    public let hash: String
    public let player: String
    public let score: Int64
    public let accuracy: Double
    public let judge: Int
    public let playedAt: Int64
    public let rawFields: [String: Double]

    public static func read(_ data: Data) throws -> ReplayHeader {
        guard data.count <= 32 * 1024 * 1024 else { throw MalodyError.invalid("单份回放超过32MiB") }
        let fields = try Wire.decode(data, wanted: [5, 6, 8])
        let hash = try Wire.text(fields, 5)
        guard hash.range(of: "^[a-f0-9]{32}$", options: .regularExpression) != nil,
              case .bytes(let payload)? = fields[8] else { throw MalodyError.invalid("回放缺少谱面标识或成绩") }
        let scoreFields = try Wire.decode(payload)
        let score = try Wire.integer(scoreFields, 5), played = try Wire.integer(scoreFields, 12)
        guard let accuracy = scoreFields[11]?.number, accuracy.isFinite, (0...100).contains(accuracy), score >= 0, played >= 0 else {
            throw MalodyError.invalid("回放成绩数值无效")
        }
        let judgeValue = scoreFields[4]?.number ?? 0
        let judge = judgeValue.isFinite && (1...5).contains(judgeValue) && judgeValue == judgeValue.rounded() ? Int(judgeValue) : 0
        var raw: [String: Double] = [:]
        for (key, value) in scoreFields { if let number = value.number, number.isFinite { raw[String(key)] = number } }
        return ReplayHeader(hash: hash, player: String(try Wire.text(fields, 6).prefix(128)), score: score,
                            accuracy: accuracy, judge: (1...5).contains(judge) ? judge : 0, playedAt: played, rawFields: raw)
    }
}
