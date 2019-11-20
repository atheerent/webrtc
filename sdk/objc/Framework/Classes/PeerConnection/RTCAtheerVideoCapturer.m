#import "WebRTC/RTCAtheerVideoCapturer.h"

#import "WebRTC/RTCLogging.h"
#import "WebRTC/RTCVideoFrameBuffer.h"

NSString *const kRTCFileVideoCapturerErrorDomain = @"org.webrtc.RTCFileVideoCapturer";

typedef NS_ENUM(NSInteger, RTCFileVideoCapturerErrorCode) {
    RTCFileVideoCapturerErrorCode_CapturerRunning = 2000,
    RTCFileVideoCapturerErrorCode_FileNotFound
};

typedef NS_ENUM(NSInteger, RTCAtheerVideoCapturerStatus) {
    RTCAtheerVideoCapturerStatusNotInitialized,
    RTCAtheerVideoCapturerStatusStarted,
    RTCAtheerVideoCapturerStatusStopped
};

@implementation RTCAtheerVideoCapturer {
    CMTime _lastPresentationTime;
    dispatch_queue_t _frameQueue;
    RTCAtheerVideoCapturerStatus _status;
}

- (void)startCapturingFromAtheerBuffer {
    RCTLogWarn(@"hao check atheer capturer started reading");
    if (_status == RTCAtheerVideoCapturerStatusStarted) {
        RCTLogWarn(@"hao check atheer video capturer already started");
        return;
    }
    _status = RTCAtheerVideoCapturerStatusStarted;
    [self readNextBuffer];
}

- (void)stopCapture {
    RTCLog(@"hao check atheer capturer stopped.");
    _status = RTCAtheerVideoCapturerStatusStopped;
}

#pragma mark - Private

- (dispatch_queue_t)frameQueue {
  if (!_frameQueue) {
    _frameQueue = dispatch_queue_create("org.webrtc.filecapturer.video", DISPATCH_QUEUE_SERIAL);
    dispatch_set_target_queue(_frameQueue,
                              dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_BACKGROUND, 0));
  }
  return _frameQueue;
}

- (void)readNextBuffer {
    if (_status == RTCAtheerVideoCapturerStatusStopped) {
      return;
    }

    //[self checkBufferInfo];
    [self publishImageBuffer];

}

/*- (void)checkBufferInfo {
    RCTLogWarn(@"hao check modifing buffer info");
    CVPixelBufferRef pixelBuffer = [RTCAtheerBuffer getTestPixelBuffer];
    CVPixelBufferLockBaseAddress(pixelBuffer, 0);
    if (pixelBuffer) {
        int bufferWidth = (int)CVPixelBufferGetWidth( pixelBuffer );
        int bufferHeight = (int)CVPixelBufferGetHeight( pixelBuffer );
        size_t bytesPerRow = CVPixelBufferGetBytesPerRow( pixelBuffer );
        uint8_t *baseAddress = CVPixelBufferGetBaseAddress( pixelBuffer );

        RCTLogWarn(@"hao check bufferWidth %d", bufferWidth );

        for ( int row = 0; row < bufferHeight; row++ ){
            uint8_t *pixel = baseAddress + row * bytesPerRow;
            for ( int column = 0; column < bufferWidth; column++ ){
                if ((row < 10) && (column < 10)) {
                    RCTLogWarn(@"hao check pixel value %d / %d / %d", pixel[0], pixel[1], pixel[2] );
                    //pixel[0] = arc4random() % 200;
                }

                pixel += 4;
            }
        }
    }
    CVPixelBufferUnlockBaseAddress(pixelBuffer, 0);
}*/

- (void)publishImageBuffer {
    //RCTLogWarn(@"hao check publishImageBuffer");

    Float64 presentationDifference = 0.1;
  int64_t presentationDifferenceRound = lroundf(presentationDifference * NSEC_PER_SEC);

  __block dispatch_source_t timer = [self createStrictTimer];
  // Strict timer that will fire |presentationDifferenceRound| ns from now and never again.
  dispatch_source_set_timer(timer,
                            dispatch_time(DISPATCH_TIME_NOW, presentationDifferenceRound),
                            DISPATCH_TIME_FOREVER,
                            0);
  dispatch_source_set_event_handler(timer, ^{
    dispatch_source_cancel(timer);
    timer = nil;

    CVPixelBufferRef pixelBuffer = [RTCAtheerBuffer getReadBuffer];
    if (!pixelBuffer) {
      dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        [self readNextBuffer];
      });
      return;
    }

    CVPixelBufferLockBaseAddress(pixelBuffer, 0);
    RTCCVPixelBuffer *rtcPixelBuffer = [[RTCCVPixelBuffer alloc] initWithPixelBuffer:pixelBuffer];
    NSTimeInterval timeStampSeconds = CACurrentMediaTime();
    int64_t timeStampNs = lroundf(timeStampSeconds * NSEC_PER_SEC);
    RTCVideoFrame *videoFrame =
        [[RTCVideoFrame alloc] initWithBuffer:rtcPixelBuffer rotation:0 timeStampNs:timeStampNs];
    CVPixelBufferUnlockBaseAddress(pixelBuffer, 0);

    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
      [self readNextBuffer];
    });

    [self.delegate capturer:self didCaptureVideoFrame:videoFrame];
  });
  dispatch_activate(timer);
}

/*- (void)publishSampleBuffer:(CMSampleBufferRef)sampleBuffer {
  CMTime presentationTime = CMSampleBufferGetPresentationTimeStamp(sampleBuffer);
  Float64 presentationDifference =
      CMTimeGetSeconds(CMTimeSubtract(presentationTime, _lastPresentationTime));
  _lastPresentationTime = presentationTime;
  int64_t presentationDifferenceRound = lroundf(presentationDifference * NSEC_PER_SEC);

  __block dispatch_source_t timer = [self createStrictTimer];
  // Strict timer that will fire |presentationDifferenceRound| ns from now and never again.
  dispatch_source_set_timer(timer,
                            dispatch_time(DISPATCH_TIME_NOW, presentationDifferenceRound),
                            DISPATCH_TIME_FOREVER,
                            0);
  dispatch_source_set_event_handler(timer, ^{
    dispatch_source_cancel(timer);
    timer = nil;

    CVPixelBufferRef pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer);
    if (!pixelBuffer) {
      CFRelease(sampleBuffer);
      dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        [self readNextBuffer];
      });
      return;
    }

    RTCCVPixelBuffer *rtcPixelBuffer = [[RTCCVPixelBuffer alloc] initWithPixelBuffer:pixelBuffer];
    NSTimeInterval timeStampSeconds = CACurrentMediaTime();
    int64_t timeStampNs = lroundf(timeStampSeconds * NSEC_PER_SEC);
    RTCVideoFrame *videoFrame =
        [[RTCVideoFrame alloc] initWithBuffer:rtcPixelBuffer rotation:0 timeStampNs:timeStampNs];
    CFRelease(sampleBuffer);

    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
      [self readNextBuffer];
    });

    [self.delegate capturer:self didCaptureVideoFrame:videoFrame];
  });
  dispatch_activate(timer);
}*/

- (dispatch_source_t)createStrictTimer {
  dispatch_source_t timer = dispatch_source_create(
      DISPATCH_SOURCE_TYPE_TIMER, 0, DISPATCH_TIMER_STRICT, [self frameQueue]);
  return timer;
}

- (void)dealloc {
  [self stopCapture];
}

@end
