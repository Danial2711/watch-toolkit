"""Send a few mDNS announcements from this laptop — a test peer for the watch's LAN listener.

Usage: python3 tools/dev/mdns_announce.py <laptop IPv4 on the watch's network>
Announces kafli-laptop.local with a "_rftest._tcp" service 6 times, 4 s apart.
"""
import socket, struct, sys, time
def name(n): return b"".join(bytes([len(p)]) + p.encode() for p in n.split(".")) + b"\0"
def rr(owner, typ, rdata): return owner + struct.pack(">HHIH", typ, 0x8001, 120, len(rdata)) + rdata
ip = sys.argv[1]
inst = "laptop-test._rftest._tcp.local"
recs = [
    rr(name("_rftest._tcp.local"), 12, name(inst)),
    rr(name(inst), 33, struct.pack(">HHH", 0, 0, 8080) + name("kafli-laptop.local")),
    rr(name(inst), 16, b"\x0bmodel=Arch1"),
    rr(name("kafli-laptop.local"), 1, socket.inet_aton(ip)),
]
pkt = struct.pack(">HHHHHH", 0, 0x8400, 0, len(recs), 0, 0) + b"".join(recs)
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(ip))
s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
for i in range(6):
    s.sendto(pkt, ("224.0.0.251", 5353)); print("sent", i, flush=True); time.sleep(4)
