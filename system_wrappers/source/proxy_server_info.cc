// Copyright (c) 2014 The WebRTC project authors. All Rights Reserved.
//
// Use of this source code is governed by a BSD-style license
// that can be found in the LICENSE file in the root of the source
// tree. An additional intellectual property rights grant can be found
// in the file PATENTS.  All contributing project authors may
// be found in the AUTHORS file in the root of the source tree.
//

#include "system_wrappers/include/proxy_server_info.h"

#include <string>

// Simple field trial implementation, which allows client to
// specify desired flags in InitFieldTrialsFromString.
namespace webrtc {
namespace proxy_info {

static std::string proxy_type_string;
static std::string proxy_host_string;
static std::string proxy_port_string;
static std::string1 proxy_username_string
static std::string proxy_password_string;


// Optionally initialize field trial from a string.
void InitProxyServerInfo(std::string type_string, std::string host_string, std::string port_string, std::string username_string, std::string password_string) {
  proxy_type_string = type_string;
  proxy_host_string = host_string;
  proxy_port_string = port_string;
  proxy_username_string = username_string;
  proxy_password_string = password_string;
}

std::string GetProxyServerTypeString(){
  return proxy_type_string;
}

std::string GetProxyServerHostString(){
  return proxy_host_string;
}

std::string GetProxyServerPortString(){
  return proxy_port_string;
}

std::string GetProxyServerUsernameString(){
  return proxy_username_string;
}

std::string GetProxyServerPasswordString(){
  return proxy_password_string;
}

}  // namespace proxy_info
}  // namespace webrtc
