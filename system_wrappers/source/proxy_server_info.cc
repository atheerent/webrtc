// Copyright (c) 2014 The WebRTC project authors. All Rights Reserved.
//
// Use of this source code is governed by a BSD-style license
// that can be found in the LICENSE file in the root of the source
// tree. An additional intellectual property rights grant can be found
// in the file PATENTS.  All contributing project authors may
// be found in the AUTHORS file in the root of the source tree.
//

#include "system_wrappers/include/proxy_server_info.h"

// Simple field trial implementation, which allows client to
// specify desired flags in InitFieldTrialsFromString.
namespace webrtc {
namespace proxy_info {

// Place static objects into a container that gets leaked so we avoid
// non-trivial destructor.
struct ProxyInfoContainer {
  std::string proxy_type_string;
  std::string proxy_host_string;
  int proxy_port_int;
  std::string proxy_username_string;
  std::string proxy_password_string;
};

ProxyInfoContainer& GetProxyContainer() {
  static ProxyInfoContainer* proxy_container = new ProxyInfoContainer();
  return *proxy_container;
}

// Optionally initialize field trial from a string.
void InitProxyServerInfo(std::string type_string, std::string host_string, int port_int, std::string username_string, std::string password_string) {
  GetProxyContainer().proxy_type_string = type_string;
  GetProxyContainer().proxy_host_string = host_string;
  GetProxyContainer().proxy_port_int = port_int;
  GetProxyContainer().proxy_username_string = username_string;
  GetProxyContainer().proxy_password_string = password_string;
}

std::string GetProxyServerTypeString(){
  return GetProxyContainer().proxy_type_string;
}

std::string GetProxyServerHostString(){
  return GetProxyContainer().proxy_host_string;
}

int GetProxyServerPortInt(){
  return GetProxyContainer().proxy_port_int;
}

std::string GetProxyServerUsernameString(){
  return GetProxyContainer().proxy_username_string;
}

std::string GetProxyServerPasswordString(){
  return GetProxyContainer().proxy_password_string;
}

}  // namespace proxy_info
}  // namespace webrtc
