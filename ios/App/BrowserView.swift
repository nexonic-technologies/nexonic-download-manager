import NexonicKit
import SwiftUI
import UIKit
import WebKit

/// The built-in browser: downloads open the Nexonic download window, long-press a link to download it, and
/// "Found on this page" lists the videos, music and files on the page. Only web pages load here.
@Observable
final class BrowserState: NSObject, WKNavigationDelegate, WKUIDelegate {
    var address = ""
    var title = ""
    var progress = 0.0
    var canBack = false
    var canForward = false
    var found: [MediaChoice] = []
    var showFound = false
    @ObservationIgnored var onDownload: ((DownloadInput) -> Void)?
    @ObservationIgnored var onTorrent: ((String) -> Void)?
    @ObservationIgnored private var observations: [NSKeyValueObservation] = []
    @ObservationIgnored private var navGen = 0
    @ObservationIgnored private var lastPageKey = ""
    @ObservationIgnored private(set) var web: WKWebView!

    override init() {
        super.init()
        web = makeWebView()
    }

    private func makeWebView() -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = .all
        let w = WKWebView(frame: .zero, configuration: config)
        w.navigationDelegate = self
        w.uiDelegate = self
        w.allowsBackForwardNavigationGestures = true
        observations = [
            w.observe(\.estimatedProgress) { [weak self] w, _ in self?.progress = w.estimatedProgress },
            w.observe(\.url) { [weak self] w, _ in
                guard let self else { return }
                let raw = w.url?.absoluteString ?? ""
                let clean = raw == "about:blank" ? "" : raw
                self.address = clean
                let key = clean.components(separatedBy: "#")[0]
                if !clean.isEmpty && key != self.lastPageKey {
                    self.lastPageKey = key
                    self.navGen += 1
                    self.found = []
                    self.scan(expectedGen: self.navGen, in: w)
                }
            },
            w.observe(\.title) { [weak self] w, _ in self?.title = self?.address.isEmpty == true ? "" : (w.title ?? "") },
            w.observe(\.canGoBack) { [weak self] w, _ in self?.canBack = w.canGoBack },
            w.observe(\.canGoForward) { [weak self] w, _ in self?.canForward = w.canGoForward },
        ]
        return w
    }

    private func scan(expectedGen: Int, in webView: WKWebView) {
        webView.evaluateJavaScript("document.documentElement.outerHTML.slice(0, 3000000)") { [weak self] result, _ in
            guard let self, expectedGen == self.navGen, let html = result as? String, let base = webView.url,
                  base.absoluteString != "about:blank" else { return }
            self.found = Probe.choices(in: html, base: base)
        }
    }

    func go(_ input: String) {
        let text = input.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        let url: URL?
        if let u = Net.webURL(text) { url = u }
        else if !text.contains(" "), text.contains(".") { url = URL(string: "https://" + text) }
        else { url = URL(string: "https://duckduckgo.com/?q=" + (text.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? text)) }
        if let url { web.load(URLRequest(url: url)) }
    }

    func startIfNeeded() { if web.url == nil { go("https://duckduckgo.com/") } }

    // MARK: Navigation

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction) async -> WKNavigationActionPolicy {
        guard let url = action.request.url, let s = url.scheme?.lowercased() else { return .cancel }
        if s == "magnet" {
            await MainActor.run { onTorrent?(url.absoluteString) }
            return .cancel
        }
        if ["http", "https", "about", "blob", "data"].contains(s) { return .allow }
        return .cancel // other apps' links are not opened from here
    }

    /// Files the page can't show (or that the server marks as attachments) go to the Nexonic download window.
    func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse) async -> WKNavigationResponsePolicy {
        let http = response.response as? HTTPURLResponse
        let attachment = http?.value(forHTTPHeaderField: "Content-Disposition")?.lowercased().hasPrefix("attachment") == true
        guard response.isForMainFrame, let url = response.response.url, Net.webURL(url.absoluteString) != nil else { return .allow }
        let isTorrent = response.response.mimeType?.lowercased() == "application/x-bittorrent" || url.pathExtension.lowercased() == "torrent"
        if isTorrent {
            await MainActor.run { onTorrent?(url.absoluteString) }
            return .cancel
        }
        guard attachment || !response.canShowMIMEType || Media.isMedia(url) else { return .allow }
        await handOver(url, suggestedName: response.response.suggestedFilename)
        return .cancel
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        navGen += 1
        found = []
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        if let u = webView.url?.absoluteString, u != "about:blank" { lastPageKey = u.components(separatedBy: "#")[0] }
        scan(expectedGen: navGen, in: webView)
    }

    /// Long-press a link: Download with Nexonic.
    func webView(_ webView: WKWebView, contextMenuConfigurationFor elementInfo: WKContextMenuElementInfo) async -> UIContextMenuConfiguration? {
        guard let link = elementInfo.linkURL, Net.webURL(link.absoluteString) != nil else { return nil }
        return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { suggested in
            let download = UIAction(title: "Download with Nexonic", image: UIImage(systemName: "arrow.down.circle")) { [weak self] _ in
                Task { await self?.handOver(Links.unwrap(link), suggestedName: nil) }
            }
            return UIMenu(children: [download] + suggested)
        }
    }

    /// Hands a link to the download window with the page as referrer and this site's cookies (same domains only).
    @MainActor
    func handOver(_ url: URL, suggestedName: String?) async {
        let cookies = await web.configuration.websiteDataStore.httpCookieStore.allCookies()
        let host = url.host?.lowercased() ?? ""
        let matched = cookies.filter { BrowserState.cookie($0.domain, matches: host) }
        for c in matched {
            HTTPCookieStorage.shared.setCookie(c) // the download session sends it only to its own domain
        }
        let cookieHeader = matched.isEmpty ? nil : HTTPCookie.requestHeaderFields(with: matched)["Cookie"]
        let ua = (try? await web.evaluateJavaScript("navigator.userAgent")) as? String
        onDownload?(DownloadInput(urls: [url], referer: web.url, userAgent: ua, suggestedName: suggestedName, cookies: cookieHeader))
    }

    static func cookie(_ domain: String, matches host: String) -> Bool {
        let d = domain.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        return host == d || host.hasSuffix("." + d)
    }

    /// Clears cookies, site data, cache and history, and leaves the active page.
    func clearData() async {
        navGen += 1
        lastPageKey = ""
        found = []
        title = ""
        address = ""
        web.stopLoading()
        web.load(URLRequest(url: URL(string: "about:blank")!))
        let store = WKWebsiteDataStore.default()
        await store.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast)
        HTTPCookieStorage.shared.removeCookies(since: .distantPast)
    }
}

struct WebView: UIViewRepresentable {
    let web: WKWebView
    func makeUIView(context: Context) -> WKWebView { web }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

struct BrowserView: View {
    @Environment(AppModel.self) private var model
    @Environment(BrowserState.self) private var browser
    @State private var editing = ""
    @FocusState private var focused: Bool

    var body: some View {
        @Bindable var browser = browser
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Button { browser.web.goBack() } label: { Image(systemName: "chevron.backward") }.disabled(!browser.canBack)
                Button { browser.web.goForward() } label: { Image(systemName: "chevron.forward") }.disabled(!browser.canForward)
                TextField("Search or type an address", text: $editing)
                    .keyboardType(.webSearch).textInputAutocapitalization(.never).autocorrectionDisabled()
                    .focused($focused)
                    .onSubmit { browser.go(editing); focused = false }
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(Color(.secondarySystemBackground), in: Capsule())
                if browser.progress < 1 && browser.progress > 0 {
                    Button { browser.web.stopLoading() } label: { Image(systemName: "xmark") }
                } else {
                    Button { browser.web.reload() } label: { Image(systemName: "arrow.clockwise") }
                }
            }
            .padding(.horizontal).padding(.vertical, 8)
            ProgressView(value: browser.progress).opacity(browser.progress > 0 && browser.progress < 1 ? 1 : 0).frame(height: 2)
            WebView(web: browser.web)
                .overlay(alignment: .bottomTrailing) {
                    if !browser.found.isEmpty {
                        Button { browser.showFound = true } label: {
                            Label("\(browser.found.count) found", systemImage: "arrow.down.circle.fill").font(.headline)
                                .padding(.horizontal, 16).padding(.vertical, 12)
                        }
                        .buttonStyle(.borderedProminent).clipShape(Capsule()).padding()
                    }
                }
        }
        .onChange(of: browser.address) { _, a in
            if !focused { editing = a.hasPrefix("https://") ? String(a.dropFirst(8)) : a }
        }
        .onAppear {
            browser.onDownload = { input in model.addInput = input }
            browser.onTorrent = { source in model.tab = .downloads; model.torrentRequest = TorrentRequest(source: source) }
            if let u = model.browserURL { browser.web.load(URLRequest(url: u)); model.browserURL = nil } else { browser.startIfNeeded() }
        }
        .onChange(of: model.browserURL) { _, u in if let u { browser.web.load(URLRequest(url: u)); model.browserURL = nil } }
        .onChange(of: model.demoShowFound) { _, show in if show { browser.showFound = true; model.demoShowFound = false } }
        .sheet(isPresented: $browser.showFound) {
            NavigationStack {
                List {
                    Section {
                        ForEach(browser.found) { c in
                            HStack {
                                let cat = FileCategory(fileName: c.url.lastPathComponent)
                                Image(systemName: cat.symbol).foregroundStyle(cat.color).frame(width: 30)
                                VStack(alignment: .leading) {
                                    Text(c.url.lastPathComponent.removingPercentEncoding ?? c.url.lastPathComponent).lineLimit(1)
                                    Text("\(c.kind.rawValue) · \(c.label)").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                }
                                Spacer()
                                Button("Download") {
                                    browser.showFound = false
                                    Task { await browser.handOver(c.url, suggestedName: nil) }
                                }
                                .buttonStyle(.bordered)
                            }
                        }
                    } footer: { Text("Streaming and copy-protected video can't be saved.") }
                }
                .navigationTitle("Found on this page")
                .navigationBarTitleDisplayMode(.inline)
            }
            .presentationDetents([.medium, .large])
        }
    }
}
