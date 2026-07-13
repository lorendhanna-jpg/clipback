import SwiftUI
import ReplayKit
import Photos

/// The system's broadcast start/stop button, pinned to our extension so the
/// picker sheet only offers Rewind.
struct BroadcastPicker: UIViewRepresentable {
    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let v = RPSystemBroadcastPickerView(frame: CGRect(x: 0, y: 0, width: 60, height: 60))
        v.preferredExtension = "app.lrtelecom.rewind.broadcast"
        v.showsMicrophoneButton = false
        return v
    }
    func updateUIView(_ uiView: RPSystemBroadcastPickerView, context: Context) {}
}

final class RewindModel: ObservableObject {
    @Published var status = "Not watching. Tap the record button and Start Broadcast — from then on the last 10 seconds of your screen are always ready."
    @Published var savedCount = 0

    init() {
        observeSaved()
        sweep()
    }

    /// The broadcast extension pings this Darwin notification after it writes
    /// a clip into the shared App Group folder.
    private func observeSaved() {
        let center = CFNotificationCenterGetDarwinNotifyCenter()
        let observer = Unmanaged.passUnretained(self).toOpaque()
        CFNotificationCenterAddObserver(center, observer, { _, observer, _, _, _ in
            guard let observer else { return }
            let model = Unmanaged<RewindModel>.fromOpaque(observer).takeUnretainedValue()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { model.sweep() }
        }, DarwinNames.saved as CFString, nil, .deliverImmediately)
    }

    /// Ask the (separately running) broadcast extension to freeze the last 10s.
    func requestSave() {
        DarwinNames.post(DarwinNames.save)
        status = "Saving the last 10 seconds…"
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { [weak self] in self?.sweep() }
    }

    /// Move any clips the extension wrote into the Photos library.
    func sweep() {
        guard let dir = AppGroup.clipsDirectory else { return }
        let files = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        let clips = files.filter { $0.pathExtension == "mp4" }
        guard !clips.isEmpty else { return }
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { auth in
            guard auth == .authorized || auth == .limited else {
                DispatchQueue.main.async { self.status = "Allow Photos access so clips can be saved to your library." }
                return
            }
            for url in clips {
                PHPhotoLibrary.shared().performChanges({
                    PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url)
                }) { ok, _ in
                    if ok {
                        try? FileManager.default.removeItem(at: url)
                        DispatchQueue.main.async {
                            self.savedCount += 1
                            self.status = "Saved to Photos ✅ (\(self.savedCount) this session)"
                        }
                    }
                }
            }
        }
    }
}

struct ContentView: View {
    @StateObject private var model = RewindModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        VStack(spacing: 28) {
            Spacer()
            Text("⏪ Rewind")
                .font(.system(size: 40, weight: .bold))
            Text(model.status)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .padding(.horizontal)

            VStack(spacing: 8) {
                BroadcastPicker()
                    .frame(width: 60, height: 60)
                Text("Start / stop watching")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Button {
                model.requestSave()
            } label: {
                Text("⏪ Save last 10 seconds")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding()
            }
            .buttonStyle(.borderedProminent)
            .padding(.horizontal, 24)

            Spacer()
            Text("Stopping the broadcast also auto-saves the last 10 seconds. Clips land in Photos. Nothing is stored or uploaded until you save — Rewind has no internet access.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
                .padding(.bottom, 16)
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active { model.sweep() }
        }
    }
}
