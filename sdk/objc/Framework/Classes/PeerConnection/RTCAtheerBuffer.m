#import "WebRTC/RTCAtheerBuffer.h"

#import "WebRTC/RTCLogging.h"

@implementation RTCAtheerBuffer

static CVPixelBufferRef pixelBuffer0 = nil;
static CVPixelBufferRef pixelBuffer1 = nil;

static int writeBuffer = 0;
static int readBuffer = 1;

+ (int)getWriteBufferId {
    return writeBuffer;
}

+ (int)getReadBufferId {
    return readBuffer;
}

+ (CVPixelBufferRef)getWriteBuffer {
    if (writeBuffer == 0) {
        return pixelBuffer0;
    } else {
        return pixelBuffer1;
    }
}

+ (CVPixelBufferRef)getReadBuffer{
    if (readBuffer == 0) {
        return pixelBuffer0;
    } else {
        return pixelBuffer1;
    }
}

+ (void)commitWriteBuffer {
    if (writeBuffer == 0) {
        writeBuffer = 1;
        readBuffer = 0;
    } else {
        writeBuffer = 0;
        readBuffer = 1;
    }
}

// Below are hao's test functions

+ (CVPixelBufferRef)getTestPixelBuffer {
    return pixelBuffer0;
}

+ (void)haotest {
    RCTLog(@"hao check haotest called");
    CGRect rect = CGRectMake(0, 0, 500, 500);
    UIGraphicsBeginImageContext(rect.size);
    CGContextRef context = UIGraphicsGetCurrentContext();
    CGContextSetFillColorWithColor(context, [[UIColor whiteColor] CGColor]);
    CGContextFillRect(context, rect);
    UIImage *image = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();

    [self setTestPixelBufferFromImage:image.CGImage];
    //[self pixelBufferFromCGImage:image.CGImage];
}

+(void)pixelBufferFromCGImage:(CGImageRef)image{
    NSDictionary *pixelAttributes = @{(id)kCVPixelBufferIOSurfacePropertiesKey : @{}};
    pixelBuffer0 = NULL;
    size_t width = CGImageGetWidth(image);
    size_t height = CGImageGetHeight(image);
    CVReturn result = CVPixelBufferCreate(kCFAllocatorDefault,
                                          width,
                                          height,
                                          kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                                          (__bridge CFDictionaryRef)(pixelAttributes),
                                          &pixelBuffer0);

    CVPixelBufferLockBaseAddress(pixelBuffer0, 0);
    uint8_t *yDestPlane = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer0, 0);
    //memcpy(yDestPlane, yPlane, width * height);
    RCTLogWarn(@"hao check pb width %zu", width );
    RCTLogWarn(@"hao check pb height %zu", height );

    for (int i = 0; i < width * height; i++) {
        yDestPlane[i] = 0xff;
    }
    uint8_t *uvDestPlane = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer0, 1);
    for (int i = 0; i < width * height / 2; i++) {
        uvDestPlane[i] = 0xff;
    }
    //memcpy(uvDestPlane, uvPlane, numberOfElementsForChroma);
    CVPixelBufferUnlockBaseAddress(pixelBuffer0, 0);

    if (result != kCVReturnSuccess) {

    }

    //CIImage *coreImage = [CIImage imageWithCVPixelBuffer:pixelBuffer]; //success!
    //CVPixelBufferRelease(pixelBuffer);
}


+ (void) setTestPixelBufferFromImage: (CGImageRef) image
{
    NSDictionary *options = @{
                              (NSString*)kCVPixelBufferCGImageCompatibilityKey : @YES,
                              (NSString*)kCVPixelBufferCGBitmapContextCompatibilityKey : @YES,
                              };

    pixelBuffer1 = NULL;
    CVReturn status = CVPixelBufferCreate(kCFAllocatorDefault, CGImageGetWidth(image),
                        CGImageGetHeight(image), kCVPixelFormatType_32ARGB, (__bridge CFDictionaryRef) options,
                        &pixelBuffer1);
    if (status!=kCVReturnSuccess) {
        NSLog(@"Operation failed");
        RCTLog(@"hao check Operation failed");
    }
    NSParameterAssert(status == kCVReturnSuccess && pixelBuffer1 != NULL);

    CVPixelBufferLockBaseAddress(pixelBuffer1, 0);
    void *pxdata = CVPixelBufferGetBaseAddress(pixelBuffer1);
    //size_t bytesPerRow = CVPixelBufferGetBytesPerRow(testPixelBuffer);

    CGColorSpaceRef rgbColorSpace = CGColorSpaceCreateDeviceRGB();
    CGContextRef context = CGBitmapContextCreate(pxdata, CGImageGetWidth(image),
                                                 CGImageGetHeight(image), 8, 4*CGImageGetWidth(image), rgbColorSpace,
                                                 kCGImageAlphaNoneSkipFirst);
    NSParameterAssert(context);

    CGContextConcatCTM(context, CGAffineTransformMakeRotation(0));
    CGAffineTransform flipVertical = CGAffineTransformMake( 1, 0, 0, -1, 0, CGImageGetHeight(image) );
    CGContextConcatCTM(context, flipVertical);
    CGAffineTransform flipHorizontal = CGAffineTransformMake( -1.0, 0.0, 0.0, 1.0, CGImageGetWidth(image), 0.0 );
    CGContextConcatCTM(context, flipHorizontal);

    CGContextDrawImage(context, CGRectMake(0, 0, CGImageGetWidth(image),
                                           CGImageGetHeight(image)), image);
    CGColorSpaceRelease(rgbColorSpace);
    CGContextRelease(context);

    CVPixelBufferUnlockBaseAddress(pixelBuffer1, 0);
}

@end
