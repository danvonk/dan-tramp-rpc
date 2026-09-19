#!/usr/bin/env python3
"""Black-box protocol regression tests. Test dependency: Python msgpack.
Usage: python3 test/protocol_test.py [--ssh dan@pi] [--java java] [--jar PATH]
The SSH JAR must already have been copied to PATH.
"""
import argparse
import os
import queue
import shlex
import struct
import subprocess
import threading
import time
import zlib
import msgpack

parser = argparse.ArgumentParser()
parser.add_argument('--ssh')
parser.add_argument('--java', default='java')
parser.add_argument('--jar', default='dist/dan-tramp-rpc.jar')
args = parser.parse_args()
command = [args.java, '--add-opens=java.base/sun.nio.fs=ALL-UNNAMED', '-jar', args.jar]
if args.ssh:
    command = ['ssh', '-o', 'BatchMode=yes', args.ssh, shlex.join(command)]
process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
responses = queue.Queue()
notifications = []
serial = 0
checks = 0

def exact(n):
    data = bytearray()
    while len(data) < n:
        chunk = process.stdout.read(n-len(data))
        if not chunk:
            raise EOFError('RPC server closed stdout')
        data.extend(chunk)
    return bytes(data)

def reader():
    try:
        while True:
            n, = struct.unpack('>I', exact(4))
            responses.put(msgpack.unpackb(exact(n), raw=False))
    except Exception as error:
        responses.put(error)

threading.Thread(target=reader, daemon=True).start()

def send(method, params=None):
    global serial
    serial += 1
    payload = msgpack.packb(dict(version='2.0', id=serial, method=method, params=params or {}), use_bin_type=True)
    process.stdin.write(struct.pack('>I', len(payload))+payload)
    process.stdin.flush()
    return serial

def receive():
    while True:
        response = responses.get(timeout=15)
        if isinstance(response, Exception):
            raise response
        if 'method' in response:
            notifications.append(response)
            continue
        return response

def call(method, **params):
    ident = send(method, params)
    response = receive()
    assert response['id'] == ident, response
    assert 'error' not in response, response
    return response['result']

def check(value):
    global checks
    assert value
    checks += 1

root = '/tmp/dan-tramp-protocol-%d-%d' % (os.getpid(), time.time_ns())
try:
    info = call('system.info')
    check(info['max_read_chunk_bytes'] == 16*1024*1024)
    call('dir.create', path=root)
    name = root+'/raw-\u03bb'
    data = bytes(range(256))*4096
    check(call('file.write', path=name, content=data)['written'] == len(data))
    r = call('file.read', path=name, offset=255, length=2000, compress=True)
    check(zlib.decompress(r['content']) == data[255:2255] and r['size'] == 2000)
    call('file.write', path=name, offset=2, content=b'xy')
    check(call('file.read', path=name, length=5)['content'] == b'\x00\x01xy\x04')
    raw = root.encode()+b'/nonutf8-\xff'
    call('file.write', path=raw, content=b'raw')
    check(call('file.read', path=raw)['content'] == b'raw')
    check(any(e['name'] == b'nonutf8-\xff' for e in call('dir.list', path=root)))
    call('file.make_symlink', target='nonutf8-\udcff'.encode('utf8', 'surrogateescape'), link_path=root+'/link')
    check(call('file.stat', path=root+'/link', lstat=True)['link_target'] == b'nonutf8-\xff')
    check(call('file.truename', path=root+'/link') == raw)
    call('file.make_hardlink', src=name, dest=root+'/hard')
    check(call('file.stat', path=name)['nlinks'] == 2)
    call('file.set_modes', path=name, mode=0o640)
    check(call('file.stat', path=name)['mode'] & 0o777 == 0o640)
    call('file.set_times', path=name, mtime=1234567890)
    check(call('file.stat', path=name)['mtime'] == 1234567890)
    check(call('file.stat', path=root+'/absent') is None)
    call('file.make_symlink', target=root, link_path=root+'/self')
    send('file.copy', dict(src=root, dest=root+'/self/child', exact_dest=True))
    check(receive()['error']['code'] == -32602)
    call('file.delete', path=root+'/self')

    send('file.rename', dict(src=name, dest=root+'/hard'))
    check(receive()['error']['data']['os_errno'] == 17)
    result = call('batch', requests=[dict(method='file.stat', params=dict(path=name)), dict(method='unknown'), dict(method='batch')])
    check(result['results'][0]['result']['size'] == len(data))
    check(result['results'][1]['error']['code'] == -32601 and result['results'][2]['error']['code'] == -32601)
    call('file.write', path=root+'/.git', content=b'')
    call('dir.create', path=root+'/a/b', parents=True)
    check(call('ancestors.scan', directory=root+'/a/b', markers=['.git'])['.git'] == root)
    check(call('highlevel.locate_dominating_file_multi', file=root+'/a/b/new', names=['.git']) == [root+'/.git'])
    out = call('process.run', cmd='sh', args=['-c', 'cat; printf err >&2'], stdin=data)
    check(out['stdout'] == data and out['stderr'] == b'err' and out['exit_code'] == 0)
    check(call('process.run', cmd='sh', args=['-c', 'printf "$SPECIAL"'], clear_env=True, env={'SPECIAL': 'value'})['stdout'] == b'value')
    send('process.run', dict(cmd='dan-nonexistent-command'))
    check(receive()['error']['data']['spawn_not_found'] is True)
    slow = send('process.run', dict(cmd='sh', args=['-c', 'sleep .3; printf slow']))
    fast = send('system.info')
    first, second = receive(), receive()
    check(first['id'] == fast and second['id'] == slow)
    pid = call('process.start', cmd='cat')['pid']
    check(call('process.write', pid=pid, data=data)['written'] == len(data))
    call('process.close_stdin', pid=pid)
    output = bytearray()
    while True:
        r = call('process.read', pid=pid, timeout_ms=200)
        output.extend(r['stdout'])
        if r['exited']:
            check(r['exit_code'] == 0)
            break
    check(output == data)
    r = call('commands.run_parallel', commands=[dict(key='ok', cmd='printf', args=['ok']), dict(key='timeout', cmd='sleep', args=['2'])], timeout_ms=100)
    check(r['ok']['stdout'] == b'ok' and r['timeout']['timed_out'])
    pty = call('process.start_pty', cmd='sh', args=['-c', 'stty size; test -t 0'], rows=31, cols=97)
    output = bytearray()
    while True:
        r = call('process.read_pty', pid=pty['pid'], timeout_ms=200)
        output.extend(r['output'])
        if r['exited']:
            check(r['exit_code'] == 0)
            break
    check(b'31 97' in output and pty['tty_name'].startswith('/dev/pts/'))
    pty = call('process.start_pty', cmd='sh', args=['-c', 'read x; stty size'], rows=24, cols=80)
    call('process.resize_pty', pid=pty['pid'], rows=42, cols=110)
    call('process.write_pty', pid=pty['pid'], data=b'go\n')
    output = bytearray()
    while True:
        r = call('process.read_pty', pid=pty['pid'], timeout_ms=200)
        output.extend(r['output'])
        if r['exited']:
            break
    check(b'42 110' in output)
    call('watch.add', path=root, recursive=True)
    call('file.write', path=root+'/a/b/watched', content=b'watch')
    for _ in range(30):
        call('system.info')
        if any(e.get('path') == (root+'/a/b/watched').encode() for n in notifications for e in n['params']['events']):
            break
        time.sleep(.02)
    check(any(e.get('path') == (root+'/a/b/watched').encode() for n in notifications for e in n['params']['events']))
    call('watch.remove', path=root)
    check(call('watch.list') == [])
    # Malformed MessagePack must produce a parse error, then recover.
    process.stdin.write(struct.pack('>I', 1)+b'\xc1'); process.stdin.flush()
    check(receive()['error']['code'] == -32700)
    check(call('system.getenv', name='DAN_NONEXISTENT_VARIABLE') is None)
    call('dir.remove', path=root, recursive=True)
    # EOF must stop children; this is tested without depending on a response.
    child = call('process.start', cmd='sleep', args=['60'])['pid']
    process.stdin.close()
    process.wait(timeout=5)
    check(process.returncode == 0)
    probe = ['kill', '-0', str(child)]
    if args.ssh:
        probe = ['ssh', args.ssh, shlex.join(probe)]
    check(subprocess.run(probe, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode != 0)
    print('%d protocol checks passed (%s)' % (checks, args.ssh or 'local'))
finally:
    if process.poll() is None:
        try:
            call('dir.remove', path=root, recursive=True)
        except Exception:
            pass
        process.stdin.close()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
    error = process.stderr.read().decode(errors='replace')
    if error:
        print(error)
