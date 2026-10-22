#!/usr/bin/env python3
"""Remove only the authenticated owner's run-tagged data through the local Gateway tunnel."""
import argparse
import json
from pathlib import Path
import time
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler
from urllib.error import HTTPError, URLError
import uuid


def cleanup(base, auth_file, run, report):
    parsed=urlsplit(base)
    if parsed.scheme!='http' or parsed.hostname!='127.0.0.1' or not parsed.port or parsed.path or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError('Cleanup must use the local SSM Gateway tunnel')
    run=str(uuid.UUID(run))
    token=next((line.partition('=')[2] for line in Path(auth_file).read_text().splitlines() if line.startswith('API_TEST_TOKEN=')),None)
    if not token:raise ValueError('Cleanup requires the authenticated verification token')
    class NoRedirect(HTTPRedirectHandler):
        def redirect_request(self,*args):return None
    opener=build_opener(NoRedirect())
    evidence={'runId':run,'success':False}
    try:
        for attempt in range(3):
            request=Request(base+'/api/metadata/verification/'+run+'/cleanup',data=b'{}',method='POST',
                            headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
            try:
                with opener.open(request,timeout=360) as response:
                    if response.status!=200:raise ValueError('Unexpected cleanup status')
                    result=json.load(response)
                    evidence.update(success=True,httpStatus=response.status,deletedPlans=result['deletedPlans'])
                    return
            except HTTPError as error:
                evidence['httpStatus']=error.code
                if error.code not in (500,502,503,504) or attempt==2:
                    raise ValueError('Verification data cleanup failed: HTTP '+str(error.code)) from None
            except (URLError,TimeoutError,OSError):
                if attempt==2:raise ValueError('Verification data cleanup unavailable') from None
            time.sleep(2)
    finally:
        report=Path(report);report.parent.mkdir(parents=True,exist_ok=True)
        report.write_text(json.dumps(evidence,indent=2)+'\n')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('base');parser.add_argument('auth_file');parser.add_argument('run');parser.add_argument('report')
    args=parser.parse_args();cleanup(args.base,args.auth_file,args.run,args.report)


if __name__=='__main__':
    try:main()
    except ValueError as error:raise SystemExit(str(error)) from None
