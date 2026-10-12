#if !canImport(Darwin)
import Foundation

/// Writes property lists as XML by hand. swift-corelibs-foundation's
/// `PropertyListSerialization.data(fromPropertyList:)` fails here with
/// NSCocoaErrorDomain 3851 (write stream error), and launchd reads XML plists
/// as happily as binary ones.
enum PlistXMLWriter {
    static func data(_ root: Any) -> Data {
        var out = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">

        """
        write(root, to: &out, depth: 0)
        out += "</plist>\n"
        return Data(out.utf8)
    }

    private static func escape(_ text: String) -> String {
        text.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
    }

    private static func write(_ value: Any, to out: inout String, depth: Int) {
        let pad = String(repeating: "\t", count: depth)

        // Containers first: they arrive as NSMutable* trees (after an edit) or plain Swift ones.
        if let dictionary = value as? [String: Any] {
            writeDictionary(dictionary, to: &out, pad: pad, depth: depth)
            return
        }
        if let dictionary = value as? NSDictionary {
            var converted: [String: Any] = [:]
            for (key, inner) in dictionary {
                if let key = key as? String { converted[key] = inner }
            }
            writeDictionary(converted, to: &out, pad: pad, depth: depth)
            return
        }
        if let array = value as? [Any] {
            writeArray(array, to: &out, pad: pad, depth: depth)
            return
        }
        if let array = value as? NSArray {
            writeArray(array.map { $0 }, to: &out, pad: pad, depth: depth)
            return
        }

        // Exact Swift types before NSNumber, because NSNumber(1) also casts to Bool.
        if type(of: value) == Bool.self, let flag = value as? Bool {
            out += "\(pad)<\(flag ? "true" : "false")/>\n"
        } else if type(of: value) == Int.self, let integer = value as? Int {
            out += "\(pad)<integer>\(integer)</integer>\n"
        } else if type(of: value) == Double.self, let real = value as? Double {
            out += "\(pad)<real>\(real)</real>\n"
        } else if let text = value as? String {
            out += "\(pad)<string>\(escape(text))</string>\n"
        } else if let data = value as? Data {
            out += "\(pad)<data>\(data.base64EncodedString())</data>\n"
        } else if let date = value as? Date {
            out += "\(pad)<date>\(ISO8601DateFormatter().string(from: date))</date>\n"
        } else if let number = value as? NSNumber {
            switch String(cString: number.objCType) {
            case "c", "B":
                out += "\(pad)<\(number.boolValue ? "true" : "false")/>\n"
            case "f", "d":
                out += "\(pad)<real>\(number.doubleValue)</real>\n"
            default:
                out += "\(pad)<integer>\(number.int64Value)</integer>\n"
            }
        } else {
            out += "\(pad)<string>\(escape(String(describing: value)))</string>\n"
        }
    }

    private static func writeDictionary(_ dictionary: [String: Any], to out: inout String, pad: String, depth: Int) {
        out += "\(pad)<dict>\n"
        for key in dictionary.keys.sorted() {
            out += "\(pad)\t<key>\(escape(key))</key>\n"
            if let inner = dictionary[key] { write(inner, to: &out, depth: depth + 1) }
        }
        out += "\(pad)</dict>\n"
    }

    private static func writeArray(_ array: [Any], to out: inout String, pad: String, depth: Int) {
        out += "\(pad)<array>\n"
        for inner in array { write(inner, to: &out, depth: depth + 1) }
        out += "\(pad)</array>\n"
    }
}
#endif
