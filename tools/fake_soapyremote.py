#!/usr/bin/env python3
"""Radio-free SoapyRemote test server for ChrissySDR process-stop testing.

This intentionally advertises a virtual radio. It never accesses SDR hardware.
"""

import argparse
import math
import socket
import struct
import threading
import time
from datetime import datetime, timezone

MAGIC = 0x53525043
TRAILER = 0x43505253
VERSION = 0x400


def log(message):
    print(f"{datetime.now(timezone.utc).isoformat()} {message}", flush=True)


def exact(sock, size):
    chunks = []
    while size:
        data = sock.recv(size)
        if not data:
            raise EOFError("socket closed")
        chunks.append(data)
        size -= len(data)
    return b"".join(chunks)


def frame(payload):
    return struct.pack("!III", MAGIC, VERSION, len(payload) + 16) + payload + struct.pack("!I", TRAILER)


def read_frame(sock):
    magic, version, length = struct.unpack("!III", exact(sock, 12))
    if magic != MAGIC or not 0x400 <= version <= 0x4FF or not 17 <= length <= 65536:
        raise ValueError("invalid RPC frame")
    body = exact(sock, length - 12)
    if struct.unpack("!I", body[-4:])[0] != TRAILER:
        raise ValueError("invalid RPC trailer")
    return body[:-4]


def integer(value):
    return b"\x02" + struct.pack("!i", value)


def string(value):
    data = value.encode()
    return b"\x06" + integer(len(data)) + data


def floating(value):
    if value == 0:
        exponent, mantissa = 0, 0
    else:
        fraction, exponent = math.frexp(value)
        mantissa = int(fraction * (1 << 53))
    return b"\x04" + integer(exponent) + b"\x03" + struct.pack("!q", mantissa)


def strings(values):
    return b"\x09" + integer(len(values)) + b"".join(string(value) for value in values)


def floats(values):
    return b"\x0a" + integer(len(values)) + b"".join(floating(value) for value in values)


def ranges(values):
    return b"\x08" + integer(len(values)) + b"".join(
        b"\x07" + floating(low) + floating(high) + floating(step)
        for low, high, step in values
    )


def kwargs(values):
    return b"\x0b" + integer(len(values)) + b"".join(
        string(key) + string(value) for key, value in values.items()
    )


def stream_header(sequence, elements):
    return struct.pack("!iiiiq", 24, sequence, elements, 0, 0)


class Stream:
    def __init__(self, identifier, direction, data, status):
        self.identifier = identifier
        self.direction = direction
        self.data = data
        self.status = status
        self.active = threading.Event()
        self.closed = threading.Event()
        threading.Thread(target=self.run, daemon=True).start()

    def run(self):
        if self.direction == 1:
            sequence = 0
            packet = b"\x00" * 4000
            while not self.closed.is_set():
                if not self.active.wait(0.1):
                    continue
                try:
                    self.data.sendall(struct.pack("!iiiiq", 4024, sequence, 1000, 0, 0) + packet)
                    sequence += 1
                    time.sleep(1 / 48)
                except (OSError, EOFError) as error:
                    log(f"RX data socket ended: {error}")
                    break
        else:
            while not self.closed.is_set():
                if not self.active.wait(0.1):
                    continue
                try:
                    header = exact(self.data, 24)
                    size, sequence, elements, _, _ = struct.unpack("!iiiiq", header)
                    if not 24 <= size <= 8192:
                        raise ValueError(f"invalid TX packet size {size}")
                    exact(self.data, size - 24)
                    self.data.sendall(stream_header(sequence + 1, 1024))
                    if sequence == 0:
                        log(f"TX stream {self.identifier}: first samples received")
                except (OSError, EOFError, ValueError) as error:
                    log(f"TX data socket ended: {error}")
                    break

    def close(self):
        self.closed.set()
        self.active.set()
        for sock in (self.data, self.status):
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            sock.close()


def handle(control, address, host):
    log(f"control connected from {address}")
    streams = {}
    try:
        with control:
            control.settimeout(30)
            while True:
                payload = read_frame(control)
                if len(payload) < 6 or payload[:2] != b"\x0f\x02":
                    raise ValueError("invalid RPC call")
                call = struct.unpack("!i", payload[2:6])[0]
                direction = payload[7] if len(payload) >= 8 and payload[6] == 0 else None
                reply = b"\x0e"
                if call == 0:
                    reply = b"\x0c" + integer(1) + kwargs({"driver": "chrissytest", "label": "ChrissySDR fake radio (NO RF)"})
                elif call == 20:
                    reply = string("ChrissySDR radio-free test server")
                elif call == 100:
                    reply = string("chrissytest")
                elif call == 101:
                    reply = string("Virtual radio (NO RF)")
                elif call == 102:
                    reply = kwargs({"station_owner": "true", "daemon_transmit_enabled": "true", "test_only": "NO RF"})
                elif call == 202:
                    reply = integer(1)
                elif call in (203, 702, 709):
                    reply = b"\x01\x00"
                elif call in (304,):
                    reply = strings(["CS16"])
                elif call == 305:
                    reply = string("CS16") + floating(32768.0)
                elif call in (306, 1402, 1405):
                    reply = b"\x12" + integer(0)
                elif call in (500, 700, 1200, 1202):
                    reply = strings([])
                elif call == 805:
                    reply = ranges([(1_000_000, 30_000_000, 1)])
                elif call == 902:
                    reply = floats([48000.0])
                elif call == 907:
                    reply = ranges([(48000, 48000, 0)])
                elif call == 905:
                    reply = floats([12000.0, 20000.0])
                elif call == 906:
                    reply = ranges([(1000, 20000, 1)])
                elif call == 901:
                    reply = floating(48000.0)
                elif call == 904:
                    reply = floating(20000.0)
                elif call == 300:
                    listener = socket.socket()
                    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                    listener.bind((host, 0))
                    listener.listen(2)
                    listener.settimeout(5)
                    port = listener.getsockname()[1]
                    control.sendall(frame(string(str(port))))
                    try:
                        data, _ = listener.accept()
                        status, _ = listener.accept()
                    finally:
                        listener.close()
                    identifier = max(streams, default=-1) + 1
                    streams[identifier] = Stream(identifier, direction, data, status)
                    log(f"{'RX' if direction == 1 else 'TX'} stream {identifier} prepared")
                    reply = integer(identifier) + string(str(port))
                elif call in (302, 303, 301):
                    identifier = struct.unpack("!i", payload[7:11])[0]
                    stream = streams.get(identifier)
                    if stream is None:
                        raise ValueError(f"unknown stream {identifier}")
                    if call == 302:
                        stream.active.set()
                        if stream.direction == 0:
                            stream.data.sendall(stream_header(0, 1024))
                            log(f"TX stream {identifier} activated (virtual only)")
                        reply = integer(0)
                    elif call == 303:
                        stream.active.clear()
                        if stream.direction == 0:
                            log(f"TX stream {identifier} deactivated")
                        reply = integer(0)
                    else:
                        stream.close()
                        streams.pop(identifier)
                        log(f"stream {identifier} closed")
                elif call == 2:
                    log("device unmade")
                elif call == 3:
                    log("control hangup")
                control.sendall(frame(reply))
                if call == 3:
                    break
    except (OSError, EOFError, ValueError) as error:
        log(f"control ended from {address}: {error}")
    finally:
        for stream in streams.values():
            if stream.direction == 0 and stream.active.is_set():
                log(f"TX stream {stream.identifier} still active when control vanished")
            stream.close()
        log(f"all virtual streams released for {address}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=55133)
    args = parser.parse_args()
    with socket.socket() as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind((args.host, args.port))
        listener.listen()
        log(f"radio-free SoapyRemote test server listening on {args.host}:{args.port}")
        while True:
            control, address = listener.accept()
            threading.Thread(target=handle, args=(control, address, args.host), daemon=True).start()


if __name__ == "__main__":
    main()
