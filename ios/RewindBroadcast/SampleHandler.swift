import ReplayKit
import VideoToolbox
import AVFoundation

/// Receives every screen frame while the broadcast runs, H.264-encodes it,
/// and keeps only the last ~12 seconds of encoded video in memory (broadcast
/// extensions get a tiny memory budget, so raw frames are never retained).
/// A "save" Darwin notification from the app — or the user stopping the
/// broadcast — writes the newest 10 seconds to the shared App Group folder,
/// where the app picks it up and moves it into Photos.
private let compressionCallback: VTCompressionOutputCallback = { refcon, _, status, _, sampleBuffer in
    guard status == noErr, let refcon, let sb = sampleBuffer, CMSampleBufferDataIsReady(sb) else { return }
    Unmanaged<SampleHandler>.fromOpaque(refcon).takeUnretainedValue().appendEncoded(sb)
}

class SampleHandler: RPBroadcastSampleHandler {

    private let keepSeconds = 12.0
    private let saveSeconds = 10.0

    private let lock = NSLock()
    private var samples: [CMSampleBuffer] = []
    private var session: VTCompressionSession?
    private let saveQueue = DispatchQueue(label: "rewind.save")

    // MARK: broadcast lifecycle

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        let center = CFNotificationCenterGetDarwinNotifyCenter()
        let observer = Unmanaged.passUnretained(self).toOpaque()
        CFNotificationCenterAddObserver(center, observer, { _, observer, _, _, _ in
            guard let observer else { return }
            let handler = Unmanaged<SampleHandler>.fromOpaque(observer).takeUnretainedValue()
            handler.saveQueue.async { handler.writeClip() }
        }, DarwinNames.save as CFString, nil, .deliverImmediately)
    }

    override func broadcastFinished() {
        // Stopping the broadcast doubles as "save that!".
        writeClip()
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleBufferType: RPSampleBufferType) {
        guard sampleBufferType == .video,
              let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        ensureSession(for: pixelBuffer)
        guard let session else { return }
        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        VTCompressionSessionEncodeFrame(
            session, imageBuffer: pixelBuffer, presentationTimeStamp: pts,
            duration: .invalid, frameProperties: nil,
            sourceFrameRefcon: nil, infoFlagsOut: nil
        )
    }

    // MARK: encoder

    private func ensureSession(for pixelBuffer: CVPixelBuffer) {
        if session != nil { return }
        var s: VTCompressionSession?
        let status = VTCompressionSessionCreate(
            allocator: nil,
            width: Int32(CVPixelBufferGetWidth(pixelBuffer)),
            height: Int32(CVPixelBufferGetHeight(pixelBuffer)),
            codecType: kCMVideoCodecType_H264,
            encoderSpecification: nil,
            imageBufferAttributes: nil,
            compressedDataAllocator: nil,
            outputCallback: compressionCallback,
            refcon: Unmanaged.passUnretained(self).toOpaque(),
            compressionSessionOut: &s
        )
        guard status == noErr, let s else { return }
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_ProfileLevel, value: kVTProfileLevel_H264_Main_AutoLevel)
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, value: NSNumber(value: 1.0))
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_AverageBitRate, value: NSNumber(value: 5_000_000))
        VTCompressionSessionPrepareToEncodeFrames(s)
        session = s
    }

    fileprivate func appendEncoded(_ sb: CMSampleBuffer) {
        lock.lock()
        samples.append(sb)
        prune()
        lock.unlock()
    }

    /// Drop old samples, but never leave the buffer starting mid-GOP: the head
    /// segment only goes once the NEXT keyframe is itself older than the cutoff.
    private func prune() {
        guard let last = samples.last else { return }
        let cutoff = CMTimeSubtract(
            CMSampleBufferGetPresentationTimeStamp(last),
            CMTime(seconds: keepSeconds, preferredTimescale: 600)
        )
        while true {
            var seenFirstKey = false
            var secondKeyIdx = -1
            for (i, s) in samples.enumerated() where isKeyframe(s) {
                if !seenFirstKey { seenFirstKey = true }
                else { secondKeyIdx = i; break }
            }
            if secondKeyIdx == -1 { return }
            if CMTimeCompare(CMSampleBufferGetPresentationTimeStamp(samples[secondKeyIdx]), cutoff) > 0 { return }
            samples.removeFirst(secondKeyIdx)
        }
    }

    private func isKeyframe(_ sb: CMSampleBuffer) -> Bool {
        guard let attachments = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false)
                as? [[CFString: Any]],
              let first = attachments.first else { return true }
        if let notSync = first[kCMSampleAttachmentKey_NotSync] as? Bool { return !notSync }
        return true
    }

    // MARK: save

    fileprivate func writeClip() {
        lock.lock()
        let snapshot = samples
        lock.unlock()
        guard snapshot.count > 1 else { return }

        let endPts = CMSampleBufferGetPresentationTimeStamp(snapshot[snapshot.count - 1])
        let wantStart = CMTimeSubtract(endPts, CMTime(seconds: saveSeconds, preferredTimescale: 600))
        var startIdx = -1
        for (i, s) in snapshot.enumerated() where isKeyframe(s) {
            if CMTimeCompare(CMSampleBufferGetPresentationTimeStamp(s), wantStart) <= 0 { startIdx = i }
        }
        if startIdx == -1 {
            startIdx = snapshot.firstIndex(where: { isKeyframe($0) }) ?? -1
        }
        guard startIdx >= 0 else { return }
        let clip = Array(snapshot[startIdx...])
        guard let format = CMSampleBufferGetFormatDescription(clip[0]),
              let dir = AppGroup.clipsDirectory else { return }

        let url = dir.appendingPathComponent("Rewind_\(Int(Date().timeIntervalSince1970)).mp4")
        guard let writer = try? AVAssetWriter(outputURL: url, fileType: .mp4) else { return }
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: nil, sourceFormatHint: format)
        input.expectsMediaDataInRealTime = false
        writer.add(input)
        writer.startWriting()
        writer.startSession(atSourceTime: CMSampleBufferGetPresentationTimeStamp(clip[0]))
        for s in clip {
            while !input.isReadyForMoreMediaData { Thread.sleep(forTimeInterval: 0.005) }
            input.append(s)
        }
        input.markAsFinished()
        let done = DispatchSemaphore(value: 0)
        writer.finishWriting { done.signal() }
        done.wait()

        if writer.status == .completed {
            DarwinNames.post(DarwinNames.saved)
        } else {
            try? FileManager.default.removeItem(at: url)
        }
    }
}
