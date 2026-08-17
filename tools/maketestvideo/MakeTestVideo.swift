import AVFoundation
import CoreVideo
import Foundation

// Writes a short solid-colour H.264 mp4, used only to give ExoPlayer something real to play
// in an instrumented test. Usage: MakeTestVideo <out.mp4> <seconds> <r> <g> <b>
let args = CommandLine.arguments
guard args.count >= 6 else {
    print("usage: MakeTestVideo <out.mp4> <seconds> <r> <g> <b>")
    exit(1)
}
let path = args[1]
let seconds = Double(args[2]) ?? 2.0
let r = UInt8(args[3]) ?? 0
let g = UInt8(args[4]) ?? 0
let b = UInt8(args[5]) ?? 0

let width = 320
let height = 240
let fps = 30

let url = URL(fileURLWithPath: path)
try? FileManager.default.removeItem(at: url)

let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
let settings: [String: Any] = [
    AVVideoCodecKey: AVVideoCodecType.h264,
    AVVideoWidthKey: width,
    AVVideoHeightKey: height,
]
let input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
input.expectsMediaDataInRealTime = false
let adaptor = AVAssetWriterInputPixelBufferAdaptor(
    assetWriterInput: input,
    sourcePixelBufferAttributes: [
        kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA),
        kCVPixelBufferWidthKey as String: width,
        kCVPixelBufferHeightKey as String: height,
    ]
)
writer.add(input)
writer.startWriting()
writer.startSession(atSourceTime: .zero)

let frameCount = Int(seconds * Double(fps))
for frame in 0..<frameCount {
    var pixelBuffer: CVPixelBuffer?
    CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_32BGRA, nil, &pixelBuffer)
    guard let buffer = pixelBuffer else { exit(2) }
    CVPixelBufferLockBaseAddress(buffer, [])
    if let base = CVPixelBufferGetBaseAddress(buffer) {
        let bytesPerRow = CVPixelBufferGetBytesPerRow(buffer)
        let ptr = base.assumingMemoryBound(to: UInt8.self)
        // Ramp the brightness across the clip so successive frames differ.
        let ramp = UInt8(truncatingIfNeeded: frame * 3)
        for y in 0..<height {
            for x in 0..<width {
                let offset = y * bytesPerRow + x * 4
                ptr[offset + 0] = b &+ ramp
                ptr[offset + 1] = g &+ ramp
                ptr[offset + 2] = r &+ ramp
                ptr[offset + 3] = 255
            }
        }
    }
    CVPixelBufferUnlockBaseAddress(buffer, [])

    while !input.isReadyForMoreMediaData { usleep(1000) }
    adaptor.append(buffer, withPresentationTime: CMTime(value: CMTimeValue(frame), timescale: CMTimeScale(fps)))
}

input.markAsFinished()
let done = DispatchSemaphore(value: 0)
writer.finishWriting { done.signal() }
done.wait()
print("wrote \(path) status=\(writer.status.rawValue) error=\(String(describing: writer.error))")
