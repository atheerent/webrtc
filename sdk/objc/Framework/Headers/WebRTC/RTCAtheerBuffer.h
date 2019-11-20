#import <Foundation/Foundation.h>

@interface RTCAtheerBuffer : NSObject

+ (int)getWriteBufferId;
+ (int)getReadBufferId;
+ (CVPixelBufferRef)getWriteBuffer;
+ (CVPixelBufferRef)getReadBuffer;
+ (void)commitWriteBuffer;

// Test Functions
+ (void)haotest;
+ (void)setTestPixelBufferFromImage: (CGImageRef) image;
+ (CVPixelBufferRef)getTestPixelBuffer;

@end
