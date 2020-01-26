//
// Copyright (c) 2015 The WebRTC project authors. All Rights Reserved.
//
// Use of this source code is governed by a BSD-style license
// that can be found in the LICENSE file in the root of the source
// tree. An additional intellectual property rights grant can be found
// in the file PATENTS.  All contributing project authors may
// be found in the AUTHORS file in the root of the source tree.
//

#ifndef SYSTEM_WRAPPERS_INCLUDE_PROXY_SERVER_INFO_H_
#define SYSTEM_WRAPPERS_INCLUDE_PROXY_SERVER_INFO_H_

#include <string>

namespace webrtc {
namespace proxy_info {

// Optionally initialize field trial from a string.
// This method can be called at most once before any other call into webrtc.
// E.g. before the peer connection factory is constructed.
// Note: trials_string must never be destroyed.
void InitProxyServerInfo(std::string type_string, std::string host_string, int port_int, std::string username_string, std::string password_string);

std::string GetProxyServerTypeString();

std::string GetProxyServerHostString();

int GetProxyServerPortInt();

std::string GetProxyServerUsernameString();

std::string GetProxyServerPasswordString();

}  // namespace proxy_info
}  // namespace webrtc

#endif  // SYSTEM_WRAPPERS_INCLUDE_PROXY_SERVER_INFO_H_
