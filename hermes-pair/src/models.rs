use serde::{Deserialize, Serialize};
use std::net::IpAddr;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct PairingPayloadV1 {
    pub v: u32,
    #[serde(rename = "type")]
    pub payload_type: String,
    pub host_id: String,
    pub name: String,
    pub host: String,
    pub port: u16,
    pub scheme: String,
    pub expires_at: u64,
    pub nonce: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub fingerprint: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
pub struct HermesStatusResponse {
    #[serde(default)]
    pub status: String,
    #[serde(rename = "authRequired", default)]
    pub auth_required: bool,
    #[serde(rename = "authProviders", default)]
    pub auth_providers: Vec<String>,
    #[serde(rename = "authFlows", default)]
    pub auth_flows: Vec<String>,
    #[serde(default)]
    pub version: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NetworkInterfaceInfo {
    pub name: String,
    pub ip: IpAddr,
    pub is_loopback: bool,
    pub is_virtual: bool,
}
