// Copyright (c) 2014 The WebRTC project authors. All Rights Reserved.
//
// Use of this source code is governed by a BSD-style license
// that can be found in the LICENSE file in the root of the source
// tree. An additional intellectual property rights grant can be found
// in the file PATENTS.  All contributing project authors may
// be found in the AUTHORS file in the root of the source tree.
//

#include "system_wrappers/include/proxyy_server_info.h"

#include <string>

// Simple field trial implementation, which allows client to
// specify desired flags in InitFieldTrialsFromString.
namespace webrtc {
namespace proxy_info {

static const char* proxy_type_string = NULL;
static const char* proxy_host_string = NULL;
static const char* proxy_port_string = NULL;
static const char* proxy_username_string = NULL;
static const char* proxy_password_string = NULL;


// Optionally initialize field trial from a string.
void InitProxyServerInfo(const char* type_string, const char* host_string, const char* port_string, const char* username_string, const char* password_string) {
  proxy_type_string = type_string;
  proxy_host_string = host_string;
  proxy_port_string = port_string;
  proxy_username_string = username_string;
  proxy_password_string = password_string;
}

const char* GetProxyServerTypeString(){
  return proxy_type_string;
}

const char* GetProxyServerHostString(){
  return proxy_host_string;
}

const char* GetProxyServerPortString(){
  return proxy_port_string;
}

const char* GetProxyServerUsernameString(){
  return proxy_username_string;
}

const char* GetProxyServerPasswordString(){
  return proxy_password_string;
}

}  // namespace proxy_info
}  // namespace webrtc
