pub use crate::models::NetworkInterfaceInfo;
use std::net::IpAddr;

pub fn is_loopback(ip: &IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => v4.is_loopback() || v4.octets()[0] == 127,
        IpAddr::V6(v6) => v6.is_loopback(),
    }
}

pub fn is_link_local(ip: &IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            let octets = v4.octets();
            octets[0] == 169 && octets[1] == 254
        }
        IpAddr::V6(v6) => v6.is_unicast_link_local(),
    }
}

pub fn is_tailscale_ip(ip: &IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            let octets = v4.octets();
            octets[0] == 100 && (octets[1] >= 64 && octets[1] <= 127)
        }
        IpAddr::V6(v6) => {
            let segments = v6.segments();
            segments[0] == 0xfd7a && segments[1] == 0x115c && segments[2] == 0xa1e0
        }
    }
}

pub fn is_private_ip(ip: &IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => v4.is_private(),
        IpAddr::V6(v6) => (v6.segments()[0] & 0xfe00) == 0xfc00,
    }
}

pub fn format_host_ip(ip: &IpAddr) -> String {
    match ip {
        IpAddr::V4(v4) => v4.to_string(),
        IpAddr::V6(v6) => format!("[{}]", v6),
    }
}

pub fn format_endpoint_url(scheme: &str, ip: &IpAddr, port: u16) -> String {
    format!("{}://{}:{}", scheme, format_host_ip(ip), port)
}

pub fn is_virtual_adapter(name: &str) -> bool {
    let lower = name.to_lowercase();
    lower.contains("docker")
        || lower.contains("veth")
        || lower.contains("virbr")
        || lower.contains("vbox")
        || lower.contains("vmnet")
        || lower.contains("hyper-v")
        || lower.contains("wsl")
        || lower.contains("vethernet")
        || lower.contains("virtual")
        || lower.contains("tap")
        || lower.contains("tun")
}

fn interface_priority(info: &NetworkInterfaceInfo) -> u32 {
    let is_priv = is_private_ip(&info.ip);
    let is_ts = is_tailscale_ip(&info.ip);
    let is_v4 = info.ip.is_ipv4();

    let base_prio = match (info.is_virtual, is_priv, is_ts) {
        (false, true, _) => 100,
        (false, false, true) => 80,
        (false, false, false) => 60,
        (true, true, _) => 40,
        (true, false, true) => 30,
        (true, false, false) => 20,
    };

    if is_v4 {
        base_prio + 1
    } else {
        base_prio
    }
}

pub fn filter_and_sort_interfaces(
    interfaces: impl IntoIterator<Item = NetworkInterfaceInfo>,
) -> Vec<NetworkInterfaceInfo> {
    let mut filtered: Vec<NetworkInterfaceInfo> = interfaces
        .into_iter()
        .filter(|iface| !iface.is_loopback && !is_loopback(&iface.ip) && !is_link_local(&iface.ip))
        .collect();

    filtered.sort_by(|a, b| {
        let prio_a = interface_priority(a);
        let prio_b = interface_priority(b);
        prio_b
            .cmp(&prio_a)
            .then_with(|| a.name.cmp(&b.name))
            .then_with(|| a.ip.cmp(&b.ip))
    });

    filtered
}

pub fn discover_network_interfaces() -> Result<Vec<NetworkInterfaceInfo>, std::io::Error> {
    let if_addrs_list = if_addrs::get_if_addrs()?;

    let mut raw_interfaces = Vec::new();
    for iface in if_addrs_list {
        match iface.addr {
            if_addrs::IfAddr::V4(ref v4_addr) => {
                let is_virt = is_virtual_adapter(&iface.name);
                let ip = IpAddr::V4(v4_addr.ip);
                let loopback = iface.is_loopback() || is_loopback(&ip);
                raw_interfaces.push(NetworkInterfaceInfo {
                    name: iface.name,
                    ip,
                    is_loopback: loopback,
                    is_virtual: is_virt,
                });
            }
            if_addrs::IfAddr::V6(ref v6_addr) => {
                let is_virt = is_virtual_adapter(&iface.name);
                let ip = IpAddr::V6(v6_addr.ip);
                let loopback = iface.is_loopback() || is_loopback(&ip);
                raw_interfaces.push(NetworkInterfaceInfo {
                    name: iface.name,
                    ip,
                    is_loopback: loopback,
                    is_virtual: is_virt,
                });
            }
        }
    }

    Ok(filter_and_sort_interfaces(raw_interfaces))
}
