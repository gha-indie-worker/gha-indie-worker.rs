#!/usr/bin/env python3
from __future__ import annotations
import argparse,json,re,sys
from pathlib import Path
SHA=re.compile(r'^[0-9a-f]{40}$'); ACTION=re.compile(r'^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+@[0-9a-f]{40}$'); IMAGE=re.compile(r'^docker://[^\s@]+@sha256:[0-9a-f]{64}$')
ALLOWED_EVENTS={'push','pull_request','workflow_dispatch'}
def validate(job):
    errors=[]
    def need(ok,code):
        if not ok: errors.append(code)
    need(job.get('schema')=='gha-indie-worker.job/v1','schema')
    for key in ('job_id','delivery_id','idempotency_key','repository','ref'): need(isinstance(job.get(key),str) and bool(job[key]),f'{key}_missing')
    need(bool(SHA.fullmatch(str(job.get('sha','')))),'sha_invalid'); need(job.get('event') in ALLOWED_EVENTS,'event_unsupported')
    permissions=job.get('permissions',{}); need(isinstance(permissions,dict),'permissions_invalid')
    if isinstance(permissions,dict):
        for name,value in permissions.items(): need(value in ('none','read'),f'permission_{name}_unsafe')
    lease=job.get('lease',{}); need(isinstance(lease,dict) and all(isinstance(lease.get(k),str) and lease[k] for k in ('id','expires_at','nonce')),'lease_invalid')
    resources=job.get('resources',{}); need(isinstance(resources,dict),'resources_invalid')
    if isinstance(resources,dict):
        for key,maximum in {'timeout_seconds':3600,'log_bytes':10485760,'artifact_bytes':1073741824,'processes':512}.items():
            value=resources.get(key); need(isinstance(value,int) and 0 < value <= maximum,f'{key}_invalid')
    isolation=job.get('isolation',{}); need(isinstance(isolation,dict),'isolation_invalid')
    if isinstance(isolation,dict):
        need(isolation.get('host_socket') is False,'host_socket_denied'); need(isolation.get('privileged') is False,'privileged_denied'); need(isolation.get('ambient_credentials') is False,'ambient_credentials_denied'); need(isolation.get('devices')==[],'devices_denied')
    steps=job.get('steps'); need(isinstance(steps,list) and 0 < len(steps) <= 100,'steps_invalid')
    if isinstance(steps,list):
        for index,step in enumerate(steps):
            need(isinstance(step,dict),f'step_{index}_invalid')
            if not isinstance(step,dict): continue
            kinds=[key for key in ('run','uses') if key in step]; need(len(kinds)==1,f'step_{index}_kind')
            if 'uses' in step: need(bool(ACTION.fullmatch(str(step['uses']))) or bool(IMAGE.fullmatch(str(step['uses']))),f'step_{index}_uses_unpinned')
    return sorted(set(errors))
def fixture():
    return {'schema':'gha-indie-worker.job/v1','job_id':'j1','delivery_id':'d1','idempotency_key':'i1','repository':'o/r','ref':'refs/heads/main','sha':'a'*40,'event':'pull_request','permissions':{'contents':'read'},'lease':{'id':'l1','expires_at':'2026-08-08T04:00:00Z','nonce':'n1'},'resources':{'timeout_seconds':900,'log_bytes':1048576,'artifact_bytes':1048576,'processes':64},'isolation':{'host_socket':False,'privileged':False,'ambient_credentials':False,'devices':[]},'steps':[{'uses':'actions/checkout@'+'b'*40},{'run':'cargo test --locked'}]}
def self_test():
    assert validate(fixture())==[]
    bad=fixture(); bad['event']='pull_request_target'; bad['permissions']={'contents':'write'}; bad['isolation']['privileged']=True; bad['steps'][0]['uses']='actions/checkout@v5'
    errors=set(validate(bad)); assert {'event_unsupported','permission_contents_unsafe','privileged_denied','step_0_uses_unpinned'} <= errors
    print('job admission self-test: ok')
def main(argv=None):
    p=argparse.ArgumentParser(); p.add_argument('file',nargs='?',type=Path); p.add_argument('--self-test',action='store_true'); a=p.parse_args(argv)
    if a.self_test: self_test(); return 0
    if not a.file: p.error('file is required unless --self-test is used')
    errors=validate(json.loads(a.file.read_text())); json.dump({'ok':not errors,'errors':errors},sys.stdout,indent=2,sort_keys=True); print(); return 0 if not errors else 2
if __name__=='__main__': raise SystemExit(main())
