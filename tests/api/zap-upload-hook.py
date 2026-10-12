"""Seed ZAP history with a real multipart upload before the API active scan."""
import hashlib
import json
import os
from pathlib import Path
from urllib.parse import urlsplit
import uuid


def zap_started(zap, target):
    spec = json.loads(Path('/zap/wrk/openapi.json').read_text())
    root = spec['servers'][0]['url'].rstrip('/')
    parsed = urlsplit(root)
    if parsed.scheme != 'http' or parsed.hostname != '127.0.0.1':
        raise ValueError('Upload fixture must target the local SSM tunnel')
    token = os.environ.get('API_TEST_TOKEN', '')
    if not token: raise RuntimeError('Authenticated Transfer scan requires a login token')
    run=str(uuid.UUID(os.environ['MMIV_VERIFICATION_RUN_ID']))
    zap.replacer.add_rule('mmiv-verification-run',True,'REQ_HEADER',False,'X-MMIV-Verification-Run',run)
    authorization = 'Authorization: Bearer ' + token + '\r\nX-MMIV-Verification-Run: ' + run + '\r\n'
    content = 'MMIV ZAP upload fixture\n'
    boundary = 'mmiv-' + uuid.uuid4().hex
    body = (f'--{boundary}\r\n'
            'Content-Disposition: form-data; name="file"; filename="zap-fixture.txt"\r\n'
            'Content-Type: text/plain\r\n\r\n' + content +
            f'\r\n--{boundary}--\r\n')
    request = (f'POST {root}/api/v1/transfers HTTP/1.1\r\n'
               f'Host: {parsed.netloc}\r\n' + authorization +
               f'Content-Type: multipart/form-data; boundary={boundary}\r\n'
               f'Content-Length: {len(body.encode())}\r\n\r\n' + body)
    messages = zap.core.send_request(request, followredirects=False)
    evidence = {'method': 'POST', 'path': '/api/v1/transfers', 'messages': messages}
    Path('/zap/wrk/upload-seed.json').write_text(json.dumps(evidence, indent=2).replace(token, "[REDACTED]") + '\n')
    if not messages or len(messages) != 1:
        raise RuntimeError('ZAP did not return the upload seed response')
    message = messages[0]
    if message.get('responseHeader', '').splitlines()[0].split()[1] != '200':
        raise RuntimeError('ZAP upload seed failed; see upload-seed.json')
    response = json.loads(message['responseBody'])
    if (response.get('success') is not True or response.get('validationResult') != 'PASSED'
            or response.get('cleanupStatus') != 'NOT_REQUIRED'):
        raise RuntimeError('ZAP upload seed integrity was not confirmed; see upload-seed.json')
    if response.get('sha256') != hashlib.sha256(content.encode()).hexdigest():
        raise RuntimeError('ZAP upload seed checksum mismatch')
    file_id = response.get('fileId')
    if not file_id or not isinstance(file_id, str):
        raise RuntimeError('Upload did not return fileId')
    # zap_started runs before the packaged API scanner imports this specification.
    for path, definition in spec['paths'].items():
        if '{fileId}' in path:
            for parameter in definition.get('parameters', []):
                if parameter['name'] == 'fileId':
                    parameter['example'] = file_id
    Path('/zap/wrk/openapi.json').write_text(json.dumps(spec, indent=2) + '\n')
    evidence['fileId'] = file_id
    evidence['read_requests'] = []
    for suffix, range_header, expected, status in [
            ('content', '', content, '200'),
            ('download', '', content, '200'),
            ('content', 'Range: bytes=0-3\r\n', 'MMIV', '206')]:
        read = (f'GET {root}/api/v1/files/{file_id}/{suffix} HTTP/1.1\r\n'
                f'Host: {parsed.netloc}\r\n' + authorization + range_header + '\r\n')
        result = zap.core.send_request(read, followredirects=False)
        evidence['read_requests'].append(result)
        Path('/zap/wrk/upload-seed.json').write_text(json.dumps(evidence, indent=2).replace(token, "[REDACTED]") + '\n')
        if (len(result) != 1 or
                result[0].get('responseHeader', '').splitlines()[0].split()[1] != status or
                result[0].get('responseBody') != expected):
            raise RuntimeError('ZAP file read seed failed; see upload-seed.json')
    evidence['validated'] = True
    Path('/zap/wrk/upload-seed.json').write_text(json.dumps(evidence, indent=2).replace(token, "[REDACTED]") + '\n')
    print('ZAP captured successful multipart POST /api/v1/transfers', flush=True)
