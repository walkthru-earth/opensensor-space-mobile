import UIKit
import VisionKit

/// The sensor sticker scanner of Device settings (#21), a thin adapter over VisionKit's
/// `DataScannerViewController`.
///
/// It reads one barcode of any symbology VisionKit recognises, as Android's Google code scanner
/// accepts every format, and hands only the decoded string to Kotlin, which runs the shared sticker
/// parser and model check. No frame is captured, kept, logged or uploaded. Kotlin asks for camera
/// access from the Scan tap before it calls `scan`, so this type never shows a prompt.
@MainActor
final class StickerScanner: NSObject, DataScannerViewControllerDelegate {
    /// True on hardware that can run the scanner (A12 Bionic and later). Scan hides otherwise.
    static var isSupported: Bool { DataScannerViewController.isSupported }

    private var onResult: ((String?) -> Void)?
    private weak var scanner: DataScannerViewController?

    /// Presents the scanner full screen over the top of `host` and calls `onResult` once, with the
    /// first barcode string read or nil on Cancel or when scanning stops. Returns false, calling
    /// nothing, when the scanner cannot open now. `isAvailable` is false while camera access is not
    /// granted, or with a Screen Time camera restriction.
    func scan(from host: UIViewController?, onResult: @escaping (String?) -> Void) -> Bool {
        guard self.onResult == nil, var top = host,
            DataScannerViewController.isSupported, DataScannerViewController.isAvailable
        else { return false }
        while let presented = top.presentedViewController { top = presented }
        let scanner = DataScannerViewController(
            recognizedDataTypes: [.barcode()],
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false,
            isPinchToZoomEnabled: true,
            isGuidanceEnabled: true,
            isHighlightingEnabled: true
        )
        scanner.delegate = self
        scanner.navigationItem.leftBarButtonItem = UIBarButtonItem(
            systemItem: .cancel,
            primaryAction: UIAction { [weak self] _ in self?.finish(nil) }
        )
        let navigation = UINavigationController(rootViewController: scanner)
        navigation.modalPresentationStyle = .fullScreen
        self.onResult = onResult
        self.scanner = scanner
        top.present(navigation, animated: true) { [weak self, weak scanner] in
            guard let self, let scanner, self.scanner === scanner else { return }
            do {
                try scanner.startScanning()
            } catch {
                self.finish(nil)
            }
        }
        return true
    }

    func dataScanner(
        _ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]
    ) {
        for item in addedItems {
            if case .barcode(let barcode) = item, let value = barcode.payloadStringValue {
                finish(value)
                return
            }
        }
    }

    func dataScanner(
        _ dataScanner: DataScannerViewController,
        becameUnavailableWithError error: DataScannerViewController.ScanningUnavailable
    ) {
        finish(nil)
    }

    /// Stops the camera, dismisses the scanner and answers once. Later calls do nothing.
    private func finish(_ value: String?) {
        guard let onResult else { return }
        self.onResult = nil
        if let scanner {
            scanner.stopScanning()
            scanner.delegate = nil
            scanner.dismiss(animated: true)
        }
        scanner = nil
        onResult(value)
    }
}
