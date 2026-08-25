use crate::config::AppConfig;
use crate::hermes::{HermesProbeClient, ProbeState};
use crate::identity::{get_display_name, get_host_id};
use crate::models::{NetworkInterfaceInfo, PairingPayloadV1};
use crate::network::{discover_network_interfaces, format_host_ip};
use crate::pairing::{
    create_pairing_payload_v2, current_unix_timestamp, encode_pairing_uri, MAX_TTL_SECONDS,
    MIN_TTL_SECONDS,
};
use crate::qr::render_egui_image;
use eframe::egui::{self, Color32, RichText, TextureHandle, Vec2};
use std::net::IpAddr;
use std::sync::mpsc::{channel, Receiver, Sender};
use std::time::{Duration, Instant};

struct ProbeRequest {
    hermes_url: Option<String>,
    scheme: String,
    port: u16,
    lan_ip: IpAddr,
}

pub struct HermesPairApp {
    config: AppConfig,
    hermes_url: Option<String>,
    scheme: String,
    port: u16,
    ttl: u64,
    fingerprint: Option<String>,
    interfaces: Vec<NetworkInterfaceInfo>,
    selected_iface_index: usize,

    current_payload: PairingPayloadV1,
    current_uri: String,
    qr_texture: Option<TextureHandle>,

    probe_state: ProbeState,
    probe_req_tx: Sender<ProbeRequest>,
    probe_res_rx: Receiver<ProbeState>,
    is_probing: bool,

    copied_banner_timer: Option<Instant>,
}

impl HermesPairApp {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        cc: &eframe::CreationContext<'_>,
        config: AppConfig,
        hermes_url: Option<String>,
        scheme: String,
        port: u16,
        explicit_interface: Option<String>,
        ttl: u64,
        fingerprint: Option<String>,
    ) -> Self {
        let ttl = ttl.clamp(MIN_TTL_SECONDS, MAX_TTL_SECONDS);

        let mut interfaces = discover_network_interfaces().unwrap_or_default();
        if interfaces.is_empty() {
            interfaces.push(NetworkInterfaceInfo {
                name: "Loopback".to_string(),
                ip: IpAddr::V4(std::net::Ipv4Addr::new(127, 0, 0, 1)),
                is_loopback: true,
                is_virtual: false,
            });
        }

        let mut selected_iface_index = 0;
        if let Some(ref target) = explicit_interface {
            let lower = target.to_lowercase();
            if let Some(idx) = interfaces.iter().position(|i| {
                i.name.to_lowercase().contains(&lower)
                    || i.ip.to_string() == *target
                    || format_host_ip(&i.ip) == *target
            }) {
                selected_iface_index = idx;
            }
        }

        let host_ip = interfaces[selected_iface_index].ip;
        let host_id = get_host_id(&config);
        let display_name = get_display_name(&config);

        let current_payload = create_pairing_payload_v2(
            host_id,
            display_name,
            format_host_ip(&host_ip),
            port,
            scheme.clone(),
            ttl,
            fingerprint.clone(),
        );
        let current_uri = encode_pairing_uri(&current_payload);
        let qr_texture = Self::build_qr_texture(&cc.egui_ctx, &current_uri);

        let (probe_req_tx, probe_req_rx) = channel::<ProbeRequest>();
        let (probe_res_tx, probe_res_rx) = channel::<ProbeState>();

        // Reusable single background worker thread & Tokio runtime
        std::thread::spawn(move || {
            let rt = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build();
            if let Ok(rt) = rt {
                let client = HermesProbeClient::new();
                while let Ok(req) = probe_req_rx.recv() {
                    let res = rt.block_on(async {
                        client
                            .probe(
                                req.hermes_url.as_deref(),
                                &req.scheme,
                                req.port,
                                Some(req.lan_ip),
                            )
                            .await
                    });
                    let _ = probe_res_tx.send(res);
                }
            }
        });

        let mut app = Self {
            config,
            hermes_url,
            scheme,
            port,
            ttl,
            fingerprint,
            interfaces,
            selected_iface_index,
            current_payload,
            current_uri,
            qr_texture,
            probe_state: ProbeState::Offline("Initial probe running...".to_string()),
            probe_req_tx,
            probe_res_rx,
            is_probing: false,
            copied_banner_timer: None,
        };

        app.trigger_probe();
        app
    }

    fn build_qr_texture(ctx: &egui::Context, uri: &str) -> Option<TextureHandle> {
        match render_egui_image(uri, 8) {
            Ok(img) => Some(ctx.load_texture("qr_code", img, egui::TextureOptions::NEAREST)),
            Err(e) => {
                eprintln!("Failed to generate QR texture: {}", e);
                None
            }
        }
    }

    fn regenerate_payload(&mut self, ctx: &egui::Context) {
        let host_ip = self.selected_ip();
        let host_id = get_host_id(&self.config);
        let display_name = get_display_name(&self.config);

        self.current_payload = create_pairing_payload_v2(
            host_id,
            display_name,
            format_host_ip(&host_ip),
            self.port,
            self.scheme.clone(),
            self.ttl,
            self.fingerprint.clone(),
        );
        self.current_uri = encode_pairing_uri(&self.current_payload);
        self.qr_texture = Self::build_qr_texture(ctx, &self.current_uri);
    }

    fn selected_ip(&self) -> IpAddr {
        self.interfaces
            .get(self.selected_iface_index)
            .map(|i| i.ip)
            .unwrap_or_else(|| IpAddr::V4(std::net::Ipv4Addr::new(127, 0, 0, 1)))
    }

    fn trigger_probe(&mut self) {
        if self.is_probing {
            return;
        }

        self.is_probing = true;
        let req = ProbeRequest {
            hermes_url: self.hermes_url.clone(),
            scheme: self.scheme.clone(),
            port: self.port,
            lan_ip: self.selected_ip(),
        };

        let _ = self.probe_req_tx.send(req);
    }

    fn refresh_interfaces(&mut self) {
        if let Ok(new_ifaces) = discover_network_interfaces() {
            if !new_ifaces.is_empty() {
                self.interfaces = new_ifaces;
                if self.selected_iface_index >= self.interfaces.len() {
                    self.selected_iface_index = 0;
                }
            }
        }
    }
}

impl eframe::App for HermesPairApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        while let Ok(state) = self.probe_res_rx.try_recv() {
            self.probe_state = state;
            self.is_probing = false;
        }

        ctx.request_repaint_after(Duration::from_millis(500));

        let now_ts = current_unix_timestamp();
        let remaining = self.current_payload.expires_at.saturating_sub(now_ts);
        let is_expired = remaining == 0;

        egui::CentralPanel::default().show(ctx, |ui| {
            ui.spacing_mut().item_spacing = Vec2::new(8.0, 8.0);

            // Title and Hermes Status Header
            ui.vertical_centered(|ui| {
                ui.heading(RichText::new("Hermes Pair").size(22.0).strong());
            });

            ui.add_space(4.0);

            // Status Card
            egui::Frame::group(ui.style())
                .inner_margin(8.0)
                .show(ui, |ui| {
                    ui.horizontal(|ui| {
                        match &self.probe_state {
                            ProbeState::Online(resp) => {
                                ui.colored_label(Color32::from_rgb(46, 204, 113), "● Running");
                                let ver = resp.version.as_deref().unwrap_or("unknown");
                                ui.label(format!("v{}", ver));
                                if resp.auth_required {
                                    ui.colored_label(
                                        Color32::from_rgb(52, 152, 219),
                                        "[Auth: Required]",
                                    );
                                } else {
                                    ui.colored_label(
                                        Color32::from_rgb(230, 126, 34),
                                        "[Auth: None]",
                                    );
                                }
                            }
                            ProbeState::LoopbackOnly { .. } => {
                                ui.colored_label(
                                    Color32::from_rgb(241, 196, 15),
                                    "● Loopback Only",
                                );
                            }
                            ProbeState::Offline(err) => {
                                ui.colored_label(Color32::from_rgb(231, 76, 60), "● Offline");
                                ui.label(RichText::new(err).size(11.0).italics());
                            }
                        }

                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if ui.button("🔄 Check").clicked() {
                                self.trigger_probe();
                            }
                        });
                    });
                });

            // Warning Banners
            if let ProbeState::LoopbackOnly { lan_error, .. } = &self.probe_state {
                ui.add_space(2.0);
                egui::Frame::NONE
                    .fill(Color32::from_rgb(60, 45, 10))
                    .stroke(egui::Stroke::new(1.0_f32, Color32::from_rgb(241, 196, 15)))
                    .inner_margin(8.0)
                    .corner_radius(4.0)
                    .show(ui, |ui| {
                        ui.horizontal(|ui| {
                            ui.label(RichText::new("⚠️").size(16.0));
                            ui.label(
                                RichText::new(format!(
                                    "Hermes is bound to loopback only (127.0.0.1).\n\
                                     LAN connection failed: {}\n\
                                     Start Hermes with LAN access:\n\
                                     hermes serve --host 0.0.0.0 --port {}",
                                    lan_error, self.port
                                ))
                                .size(11.5)
                                .color(Color32::from_rgb(241, 196, 15)),
                            );
                        });
                    });
            }

            if let ProbeState::Online(resp) = &self.probe_state {
                if !resp.auth_required {
                    ui.add_space(2.0);
                    egui::Frame::NONE
                        .fill(Color32::from_rgb(50, 25, 20))
                        .stroke(egui::Stroke::new(1.0_f32, Color32::from_rgb(230, 126, 34)))
                        .inner_margin(8.0)
                        .corner_radius(4.0)
                        .show(ui, |ui| {
                            ui.label(
                                RichText::new(
                                    "⚠️ Warning: Hermes is reachable over the network without authentication. \
                                     Do not expose beyond a trusted LAN.",
                                )
                                .size(11.5)
                                .color(Color32::from_rgb(230, 126, 34)),
                            );
                        });
                }
            }

            ui.add_space(4.0);

            // Network Interface Selector
            ui.horizontal(|ui| {
                ui.label("Network Interface:");
                let current_label =
                    if let Some(iface) = self.interfaces.get(self.selected_iface_index) {
                        format!("{} ({})", iface.name, format_host_ip(&iface.ip))
                    } else {
                        "None".to_string()
                    };

                let prev_idx = self.selected_iface_index;
                egui::ComboBox::from_id_salt("interface_select")
                    .selected_text(current_label)
                    .show_ui(ui, |ui| {
                        for (idx, iface) in self.interfaces.iter().enumerate() {
                            let tag = if iface.is_virtual {
                                "[Virt]"
                            } else if crate::network::is_private_ip(&iface.ip) {
                                "[LAN]"
                            } else {
                                ""
                            };
                            let label = format!("{} ({}) {}", iface.name, format_host_ip(&iface.ip), tag);
                            ui.selectable_value(&mut self.selected_iface_index, idx, label);
                        }
                    });

                if prev_idx != self.selected_iface_index {
                    self.regenerate_payload(ctx);
                    self.trigger_probe();
                }
            });

            // Address display
            ui.horizontal(|ui| {
                ui.label(RichText::new("Target Address:").strong());
                ui.monospace(format!(
                    "{}://{}:{}",
                    self.scheme,
                    format_host_ip(&self.selected_ip()),
                    self.port
                ));
            });

            ui.add_space(4.0);

            // Center QR Code or Placeholder Card
            ui.vertical_centered(|ui| {
                match &self.probe_state {
                    ProbeState::Online(_) => {
                        if is_expired {
                            egui::Frame::group(ui.style())
                                .inner_margin(32.0)
                                .show(ui, |ui| {
                                    ui.label(
                                        RichText::new("⚠️ QR Code Expired")
                                            .color(Color32::from_rgb(231, 76, 60))
                                            .strong()
                                            .size(16.0),
                                    );
                                    ui.add_space(8.0);
                                    ui.label(
                                        "Click [ 🔄 Regenerate QR ] below to create a fresh pairing code.",
                                    );
                                });
                        } else if let Some(ref texture) = self.qr_texture {
                            ui.image((texture.id(), Vec2::new(260.0, 260.0)));
                            ui.add_space(6.0);
                            let mins = remaining / 60;
                            let secs = remaining % 60;
                            ui.label(
                                RichText::new(format!("Expires in {:02}:{:02}", mins, secs))
                                    .size(13.0)
                                    .color(Color32::LIGHT_GRAY),
                            );
                        } else {
                            ui.label("Failed to render QR code");
                        }
                    }
                    ProbeState::LoopbackOnly { .. } => {
                        egui::Frame::group(ui.style())
                            .inner_margin(32.0)
                            .show(ui, |ui| {
                                ui.colored_label(
                                    Color32::from_rgb(241, 196, 15),
                                    RichText::new("⚠️ Pairing QR Hidden").size(15.0).strong(),
                                );
                                ui.add_space(8.0);
                                ui.label(
                                    "Hermes is running locally on 127.0.0.1, but unreachable on this LAN interface.\n\
                                     Start Hermes with --host 0.0.0.0 to enable network pairing.",
                                );
                            });
                    }
                    ProbeState::Offline(err) => {
                        egui::Frame::group(ui.style())
                            .inner_margin(32.0)
                            .show(ui, |ui| {
                                ui.colored_label(
                                    Color32::from_rgb(231, 76, 60),
                                    RichText::new("● Hermes Offline").size(15.0).strong(),
                                );
                                ui.add_space(8.0);
                                ui.label(format!(
                                    "Hermes is not responding on this endpoint ({}).\n\
                                     Start Hermes Agent and click [ 🔄 Check ] to connect.",
                                    err
                                ));
                            });
                    }
                }
            });

            ui.add_space(6.0);

            let can_copy = self.probe_state.is_online() && !is_expired;

            // Action Buttons
            ui.horizontal(|ui| {
                ui.columns(2, |cols| {
                    if cols[0].button("🔄 Regenerate QR").clicked() {
                        self.refresh_interfaces();
                        self.regenerate_payload(ctx);
                        self.trigger_probe();
                    }

                    if cols[1]
                        .add_enabled(can_copy, egui::Button::new("📋 Copy Link"))
                        .clicked()
                    {
                        ctx.copy_text(self.current_uri.clone());
                        self.copied_banner_timer = Some(Instant::now());
                    }
                });
            });

            // Copied banner notification
            if let Some(timer) = self.copied_banner_timer {
                if timer.elapsed().as_secs() < 2 {
                    ui.vertical_centered(|ui| {
                        ui.label(
                            RichText::new("✓ Pairing link copied to clipboard!")
                                .color(Color32::from_rgb(46, 204, 113))
                                .size(12.0),
                        );
                    });
                } else {
                    self.copied_banner_timer = None;
                }
            }
        });
    }
}
