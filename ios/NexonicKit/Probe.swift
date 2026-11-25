import Foundation

public struct ProbeResult: Sendable {
    public var finalURL: URL
    public var fileName: String
    public var size: Int64?
    public var resumable: Bool
    public var mime: String?
    public var isPage: Bool { (mime ?? "").lowercased().contains("html") }
}

/// A file or video found on a shared page.
public struct MediaChoice: Identifiable, Hashable, Sendable {
    public var id: String { url.absoluteString }
    public var url: URL
    public var kind: Media.Kind
    public var label: String

    public init(url: URL, kind: Media.Kind, label: String) { self.url = url; self.kind = kind; self.label = label }
}

public struct ServerError: LocalizedError, Sendable {
    public let status: Int
    public var errorDescription: String? { Downloader.message(status: status) }
}

/// Sends only caller-provided browser headers/cookies and strips them on cross-origin redirects.
public enum Probe {
    private final class RedirectGuard: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
        let origin: URL
        init(origin: URL) { self.origin = origin }

        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
            guard let next = request.url, let scheme = next.scheme?.lowercased(), scheme == "http" || scheme == "https" else {
                completionHandler(nil); return
            }
            if !Net.sameSite(next, origin) {
                var stripped = request
                stripped.setValue(nil, forHTTPHeaderField: "Cookie")
                stripped.setValue(nil, forHTTPHeaderField: "Referer")
                stripped.setValue(nil, forHTTPHeaderField: "Authorization")
                completionHandler(stripped)
                return
            }
            completionHandler(request)
        }
    }

    static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 20
        c.httpShouldSetCookies = false
        return URLSession(configuration: c)
    }()

    static func makeRequest(url: URL, referer: URL? = nil, userAgent: String? = nil, cookies: String? = nil, range: String? = nil) -> URLRequest {
        var req = URLRequest(url: url)
        if let range { req.setValue(range, forHTTPHeaderField: "Range") }
        if let ua = userAgent, !ua.isEmpty { req.setValue(ua, forHTTPHeaderField: "User-Agent") }
        if let r = referer, Net.sameSite(url, r) { req.setValue(r.absoluteString, forHTTPHeaderField: "Referer") }
        if let cookies, !cookies.isEmpty { req.setValue(cookies, forHTTPHeaderField: "Cookie") }
        return req
    }

    /// Asks for the first byte only: name, size, type and whether the server can resume.
    public static func run(_ url: URL, referer: URL? = nil, userAgent: String? = nil, cookies: String? = nil) async throws -> ProbeResult {
        let req = makeRequest(url: url, referer: referer, userAgent: userAgent, cookies: cookies, range: "bytes=0-0")
        let guardDelegate = (cookies?.isEmpty == false || referer != nil) ? RedirectGuard(origin: url) : nil
        let (bytes, response) = try await session.bytes(for: req, delegate: guardDelegate)
        bytes.task.cancel()
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        guard http.statusCode < 400 else { throw ServerError(status: http.statusCode) }
        let finalURL = http.url ?? url
        let cr = http.value(forHTTPHeaderField: "Content-Range")
        let ranged = http.statusCode == 206 && cr != nil
        let size = ranged ? Net.total(contentRange: cr) : (http.expectedContentLength >= 0 ? http.expectedContentLength : nil)
        var name = FileNames.fromContentDisposition(http.value(forHTTPHeaderField: "Content-Disposition")) ?? FileNames.fromURL(finalURL)
        if (name as NSString).pathExtension.isEmpty, let ext = Probe.ext(forMime: http.mimeType) { name += ".\(ext)" }
        return ProbeResult(finalURL: finalURL, fileName: name, size: size, resumable: ranged, mime: http.mimeType)
    }

    static func ext(forMime mime: String?) -> String? {
        [
            "application/pdf": "pdf", "application/zip": "zip", "video/mp4": "mp4", "audio/mpeg": "mp3", "audio/mp4": "m4a",
            "video/webm": "webm", "video/quicktime": "mov", "image/jpeg": "jpg", "image/png": "png", "application/epub+zip": "epub",
        ][mime?.lowercased() ?? ""]
    }

    /// Videos, music and files on a web page: <video>/<audio>/<source>, og:video and links to files.
    /// Streams (.m3u8, .mpd, .ts, .m4s) and copy-protected video are not offered.
    public static func scan(page: URL, referer: URL? = nil, userAgent: String? = nil, cookies: String? = nil) async -> [MediaChoice] {
        let req = makeRequest(url: page, referer: referer, userAgent: userAgent, cookies: cookies)
        let guardDelegate = (cookies?.isEmpty == false || referer != nil) ? RedirectGuard(origin: page) : nil
        guard let result = try? await session.bytes(for: req, delegate: guardDelegate) else { return [] }
        let bytes = result.0
        var data = Data()
        do {
            for try await b in bytes {
                data.append(b)
                if data.count > 3_000_000 { break }
            }
        } catch {}
        bytes.task.cancel()
        let html = String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
        return choices(in: html, base: page)
    }

    public static func choices(in html: String, base: URL) -> [MediaChoice] {
        var out: [MediaChoice] = []
        var seen = Set<String>()
        func add(_ raw: String, _ kind: Media.Kind?, _ label: String?) {
            let decoded = raw.replacingOccurrences(of: "&amp;", with: "&").trimmingCharacters(in: .whitespaces)
            guard let u = URL(string: decoded, relativeTo: base)?.absoluteURL, let s = u.scheme?.lowercased(),
                  s == "http" || s == "https", !Media.isStream(u), !seen.contains(u.absoluteString) else { return }
            let k = kind ?? Media.kind(u)
            if kind == nil && !Media.looksDownloadable(u) { return }
            seen.insert(u.absoluteString)
            let name = u.lastPathComponent.removingPercentEncoding ?? u.lastPathComponent
            let text = [label?.isEmpty == false ? label : nil, Media.qualityLabel(u), name].compactMap { $0 }.first ?? name
            out.append(MediaChoice(url: u, kind: k, label: text))
        }
        func attr(_ tag: String, _ name: String) -> String? {
            let p = "\\b\(name)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')"
            guard let re = try? NSRegularExpression(pattern: p, options: .caseInsensitive),
                  let m = re.firstMatch(in: tag, range: NSRange(tag.startIndex..., in: tag)) else { return nil }
            for g in [2, 3] { if let r = Range(m.range(at: g), in: tag) { return String(tag[r]) } }
            return nil
        }
        func tags(_ pattern: String) -> [String] {
            guard let re = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else { return [] }
            return re.matches(in: html, range: NSRange(html.startIndex..., in: html)).compactMap { Range($0.range, in: html).map { String(html[$0]) } }
        }
        var inAudio = false
        for t in tags("<(video|audio|source)\\b[^>]*>") {
            let lower = t.lowercased()
            if lower.hasPrefix("<video") { inAudio = false } else if lower.hasPrefix("<audio") { inAudio = true }
            if let src = attr(t, "src") {
                let label = attr(t, "label") ?? attr(t, "title") ?? attr(t, "res").map { "\($0)p" } ?? attr(t, "size").map { "\($0)p" }
                add(src, inAudio ? .audio : .video, label)
            }
        }
        for t in tags("<meta\\b[^>]*>") {
            if let p = attr(t, "property") ?? attr(t, "name"), ["og:video", "og:video:url", "og:video:secure_url"].contains(p.lowercased()),
               let c = attr(t, "content") { add(c, .video, nil) }
        }
        for t in tags("<a\\b[^>]*>") { if let h = attr(t, "href") { add(h, nil, nil) } }
        return out.sorted { order($0.kind) < order($1.kind) }
    }

    private static func order(_ k: Media.Kind) -> Int { k == .video ? 0 : k == .audio ? 1 : 2 }
}

/// Adding and starting downloads, and putting finished files into Files (app only).
public enum Library {
    /// The app's Documents folder: visible in Files › On My iPhone › Nexonic.
    public static var documents: URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0] }

    @discardableResult
    public static func add(_ job: DownloadJob, startNow: Bool, sessionID: String) -> DownloadJob {
        var j = job
        j.status = startNow ? .queued : .later
        let stored = Store.shared.add(j)
        if startNow, stored.id == j.id { Downloader.shared(sessionID).start(stored) }
        return stored
    }

    /// Moves finished files from the shared folder into Files (Nexonic/Video, /Music… when folders by type are on).
    public static func importFinished() {
        let fm = FileManager.default
        for job in Store.shared.load() where job.incomingPath != nil && job.savedPath == nil {
            let source = AppGroup.folder.appendingPathComponent(job.incomingPath!)
            guard fm.fileExists(atPath: source.path) else { continue }
            var folder = documents
            var relative = ""
            if SharedSettings.categoryFolders, let sub = job.category.folder {
                folder = folder.appendingPathComponent(sub, isDirectory: true)
                relative = sub + "/"
            }
            try? fm.createDirectory(at: folder, withIntermediateDirectories: true)
            let target = FileNames.unique(job.fileName, in: folder)
            do {
                try fm.moveItem(at: source, to: target)
                try? fm.removeItem(at: source.deletingLastPathComponent())
                Store.shared.update(job.id) { j in
                    j.savedPath = relative + target.lastPathComponent
                    j.fileName = target.lastPathComponent
                    j.incomingPath = nil
                }
            } catch {
                Store.shared.update(job.id) { $0.error = "Could not save to Files: \(error.localizedDescription)" }
            }
        }
    }

    public static func fileURL(_ job: DownloadJob) -> URL? {
        job.savedPath.map { documents.appendingPathComponent($0) }
    }
}
