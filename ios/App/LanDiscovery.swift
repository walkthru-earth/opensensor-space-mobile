import Foundation
import Network
import OpenSensorShared

/// Browses only the firmware's AQ Sync service. No sockets, tokens or broad network probes.
final class LanDiscovery {
    private let state: CompanionState
    private var browser: NWBrowser?
    private var timer: Timer?

    init(state: CompanionState) { self.state = state }

    func scan() {
        stop()
        state.lanScanStarted()
        let browser = NWBrowser(for: .bonjour(type: state.lanServiceType, domain: "local."), using: .tcp)
        self.browser = browser
        browser.stateUpdateHandler = { [weak self, weak browser] update in
            guard let self, let browser, self.browser === browser else { return }
            switch update {
            case .waiting:
                // Waiting can mean Wi-Fi is unavailable as well as permission denial.
                // Do not claim that the user denied access without evidence.
                self.state.lanScanWaiting()
            case .ready:
                self.state.lanScanReady()
            case .failed:
                self.state.lanScanUnavailable()
                self.stop()
            default: break
            }
        }
        browser.browseResultsChangedHandler = { [weak self, weak browser] results, _ in
            guard let self, let browser, self.browser === browser else { return }
            let names = results.compactMap { result -> String? in
                guard case .service(let name, let type, let domain, _) = result.endpoint,
                      type.trimmingCharacters(in: CharacterSet(charactersIn: ".")) == self.state.lanServiceType,
                      domain == "local." else { return nil }
                return name
            }
            self.state.lanDevicesFound(names: names)
        }
        browser.start(queue: .main)
        timer = Timer.scheduledTimer(withTimeInterval: 40, repeats: false) { [weak self] _ in self?.stop() }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        let old = browser
        browser = nil
        old?.stateUpdateHandler = nil
        old?.browseResultsChangedHandler = nil
        old?.cancel()
        state.lanScanStopped()
    }
}
