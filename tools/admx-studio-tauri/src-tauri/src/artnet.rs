use std::net::UdpSocket;
use std::sync::atomic::{AtomicU8, Ordering};

pub struct ArtNetSender {
    socket: UdpSocket,
    sequence: AtomicU8,
}

impl ArtNetSender {
    pub fn new() -> Result<Self, String> {
        let socket = UdpSocket::bind("0.0.0.0:0").map_err(|e| format!("UDP bind error: {e}"))?;
        socket.set_broadcast(true).map_err(|e| format!("UDP broadcast set error: {e}"))?;
        Ok(Self {
            socket,
            sequence: AtomicU8::new(1),
        })
    }

    pub fn send_dmx(&self, universe: u16, target_addr: &str, channels: &[u8]) -> Result<usize, String> {
        let mut packet = vec![0u8; 18 + 512];
        // 1. Signature "Art-Net\0"
        packet[0..8].copy_from_slice(b"Art-Net\0");
        // 2. OpCode 0x5000 (OpDmx, Little Endian)
        packet[8] = 0x00;
        packet[9] = 0x50;
        // 3. ProtVer 14 (Big Endian)
        packet[10] = 0x00;
        packet[11] = 0x0e;
        // 4. Sequence
        let seq = self.sequence.fetch_add(1, Ordering::Relaxed);
        packet[12] = if seq == 0 { 1 } else { seq };
        // 5. Physical
        packet[13] = 0x00;
        // 6. SubUni & Net
        packet[14] = (universe & 0xFF) as u8;
        packet[15] = ((universe >> 8) & 0x7F) as u8;
        // 7. Length 512 (Big Endian)
        packet[16] = 0x02;
        packet[17] = 0x00;

        // 8. Payload (DMX Channels)
        let copy_len = channels.len().min(512);
        packet[18..18 + copy_len].copy_from_slice(&channels[..copy_len]);

        self.socket
            .send_to(&packet, target_addr)
            .map_err(|e| format!("UDP send error: {e}"))
    }
}
