#!/usr/bin/env python3
"""Fail-closed validator for authoritative mixed-OS fleet inventory snapshots."""
from __future__ import annotations
import argparse, json, re, sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

SCHEMA='gha-indie-worker.fleet-inventory/v1'
OS={'linux','macos','windows'}; ARCH={'x64','arm64'}
TRUST={'public-untrusted','public-trusted','private-build','release-signing'}
STATES={'enrolling','healthy','busy','draining','maintenance','quarantined','offline','revoked'}
PATCH={'current','stale','emergency'}; RINGS={'canary','stable','emergency'}
SCHED={'healthy','busy'}
HOST=re.compile(r'^[a-z0-9][a-z0-9.-]{2,63}$'); NAME=re.compile(r'^[a-z0-9][a-z0-9._-]{0,63}$')
KEY=re.compile(r'^device:[a-z0-9][a-z0-9.-]{2,63}:[0-9a-f]{16}$')
DIGEST=re.compile(r'^sha256:[0-9a-f]{64}$'); SEMVER=re.compile(r'^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')
REQ={'host_id','owner','location','asset_reference','os','architecture','hardware_class','trust_tier','state','profiles','image_generation','patch_state','maintenance_ring','agent_version','protocol_version','device_identity','capability_digest','capacity','recovery','last_seen_at','accepts_untrusted_jobs'}
OPT={'quarantine_reason','support_reference','decommissioned_at'}; KEYS=REQ|OPT

def ts(value: Any, code: str, errors: list[str]):
    if not isinstance(value,str) or not value.endswith('Z'):
        errors.append(code); return None
    try: value=datetime.fromisoformat(value[:-1]+'+00:00')
    except ValueError: errors.append(code); return None
    if value.utcoffset()!=timedelta(0): errors.append(code); return None
    return value.astimezone(timezone.utc)

def text(value: Any, maximum=128): return isinstance(value,str) and 0<len(value)<=maximum

def exact(value: dict, allowed: set[str], required: set[str], prefix: str, errors: list[str]):
    for key in sorted(required-set(value)): errors.append(f'{prefix}.{key}.missing')
    for key in sorted(set(value)-allowed): errors.append(f'{prefix}.{key}.unknown')

def validate(data: Any) -> list[str]:
    e=[]
    if not isinstance(data,dict): return ['inventory.invalid']
    exact(data,{'schema','generated_at','hosts'},{'schema','generated_at','hosts'},'inventory',e)
    if data.get('schema')!=SCHEMA: e.append('inventory.schema')
    generated=ts(data.get('generated_at'),'inventory.generated_at',e)
    hosts=data.get('hosts')
    if not isinstance(hosts,list): return sorted(set(e+['inventory.hosts']))
    seen=set()
    for i,h in enumerate(hosts):
        p=f'hosts[{i}]'
        if not isinstance(h,dict): e.append(p+'.invalid'); continue
        exact(h,KEYS,REQ,p,e)
        hid=h.get('host_id')
        if not isinstance(hid,str) or not HOST.fullmatch(hid): e.append(p+'.host_id')
        elif hid in seen: e.append(p+'.duplicate_host_id')
        else: seen.add(hid)
        for k in ('owner','location','asset_reference','hardware_class','image_generation'):
            if not text(h.get(k)): e.append(f'{p}.{k}')
        if h.get('os') not in OS: e.append(p+'.os')
        if h.get('architecture') not in ARCH: e.append(p+'.architecture')
        if h.get('trust_tier') not in TRUST: e.append(p+'.trust_tier')
        state=h.get('state')
        if state not in STATES: e.append(p+'.state')
        if h.get('patch_state') not in PATCH: e.append(p+'.patch_state')
        if h.get('maintenance_ring') not in RINGS: e.append(p+'.maintenance_ring')
        if not isinstance(h.get('agent_version'),str) or not SEMVER.fullmatch(h['agent_version']): e.append(p+'.agent_version')
        if h.get('protocol_version')!=1: e.append(p+'.protocol_version')
        if not isinstance(h.get('capability_digest'),str) or not DIGEST.fullmatch(h['capability_digest']): e.append(p+'.capability_digest')

        profiles=h.get('profiles'); names=[]; digests=set()
        if not isinstance(profiles,list) or not profiles or len(profiles)>64: e.append(p+'.profiles')
        else:
            for j,profile in enumerate(profiles):
                q=f'{p}.profiles[{j}]'
                if not isinstance(profile,dict): e.append(q+'.invalid'); continue
                exact(profile,{'name','digest'},{'name','digest'},q,e)
                name=profile.get('name'); digest=profile.get('digest')
                if not isinstance(name,str) or not NAME.fullmatch(name): e.append(q+'.name')
                else: names.append(name)
                if not isinstance(digest,str) or not DIGEST.fullmatch(digest): e.append(q+'.digest')
                elif digest in digests: e.append(q+'.duplicate_digest')
                else: digests.add(digest)
            if names!=sorted(names): e.append(p+'.profiles.unsorted')
            if len(names)!=len(set(names)): e.append(p+'.profiles.duplicate_name')

        identity=h.get('device_identity'); revoked=None; expires=None
        if not isinstance(identity,dict): e.append(p+'.device_identity')
        else:
            exact(identity,{'key_id','expires_at','revoked'},{'key_id','expires_at','revoked'},p+'.device_identity',e)
            if not isinstance(identity.get('key_id'),str) or not KEY.fullmatch(identity['key_id']): e.append(p+'.device_identity.key_id')
            expires=ts(identity.get('expires_at'),p+'.device_identity.expires_at',e)
            revoked=identity.get('revoked')
            if not isinstance(revoked,bool): e.append(p+'.device_identity.revoked')

        capacity=h.get('capacity'); maximum=active=None
        if not isinstance(capacity,dict): e.append(p+'.capacity')
        else:
            exact(capacity,{'max_concurrent_jobs','active_jobs'},{'max_concurrent_jobs','active_jobs'},p+'.capacity',e)
            maximum=capacity.get('max_concurrent_jobs'); active=capacity.get('active_jobs')
            if not isinstance(maximum,int) or isinstance(maximum,bool) or not 1<=maximum<=32: e.append(p+'.capacity.max_concurrent_jobs')
            if not isinstance(active,int) or isinstance(active,bool) or not 0<=active<=32: e.append(p+'.capacity.active_jobs')
            if isinstance(maximum,int) and isinstance(active,int) and active>maximum: e.append(p+'.capacity.exhausted')

        recovery=h.get('recovery'); drill=None
        if not isinstance(recovery,dict): e.append(p+'.recovery')
        else:
            exact(recovery,{'method','last_drill_at','maximum_recovery_minutes'},{'method','last_drill_at','maximum_recovery_minutes'},p+'.recovery',e)
            if not text(recovery.get('method')): e.append(p+'.recovery.method')
            drill=ts(recovery.get('last_drill_at'),p+'.recovery.last_drill_at',e)
            limit=recovery.get('maximum_recovery_minutes')
            if not isinstance(limit,int) or isinstance(limit,bool) or not 1<=limit<=1440: e.append(p+'.recovery.maximum_recovery_minutes')

        last=ts(h.get('last_seen_at'),p+'.last_seen_at',e)
        accepts=h.get('accepts_untrusted_jobs'); trust=h.get('trust_tier')
        if not isinstance(accepts,bool): e.append(p+'.accepts_untrusted_jobs')
        if generated and last and last>generated+timedelta(seconds=30): e.append(p+'.last_seen_future')
        if generated and drill and drill>generated: e.append(p+'.recovery.last_drill_future')
        if state in SCHED:
            if h.get('patch_state')!='current': e.append(p+'.schedulable_patch_state')
            if revoked is not False: e.append(p+'.schedulable_identity_revoked')
            if generated and expires and expires<=generated: e.append(p+'.schedulable_identity_expired')
            if generated and last and generated-last>timedelta(minutes=2): e.append(p+'.schedulable_heartbeat_stale')
        if state=='busy' and active==0: e.append(p+'.busy_without_active_job')
        if state=='healthy' and isinstance(maximum,int) and isinstance(active,int) and active>=maximum: e.append(p+'.healthy_without_capacity')
        reason=h.get('quarantine_reason')
        if state=='quarantined':
            if not text(reason,512): e.append(p+'.quarantine_reason')
            if accepts is not False: e.append(p+'.quarantined_accepts_untrusted')
        elif reason not in (None,''): e.append(p+'.quarantine_reason_unexpected')
        if state=='revoked':
            if revoked is not True: e.append(p+'.revoked_identity_active')
            if accepts is not False: e.append(p+'.revoked_accepts_untrusted')
        if trust=='release-signing' and accepts is not False: e.append(p+'.release_signing_accepts_untrusted')
        if accepts is True and trust!='public-untrusted': e.append(p+'.untrusted_jobs_wrong_tier')
    return sorted(set(e))

def fixture():
    return {'schema':SCHEMA,'generated_at':'2026-08-08T04:00:00Z','hosts':[{'host_id':'mac-lab-01','owner':'ci-ops','location':'lab-a','asset_reference':'asset-mac-001','os':'macos','architecture':'arm64','hardware_class':'apple-silicon','trust_tier':'public-untrusted','state':'healthy','profiles':[{'name':'macos-xcode','digest':'sha256:'+'1'*64}],'image_generation':'mac-2026-08-a','patch_state':'current','maintenance_ring':'stable','agent_version':'0.1.0','protocol_version':1,'device_identity':{'key_id':'device:mac-lab-01:0123456789abcdef','expires_at':'2026-08-09T04:00:00Z','revoked':False},'capability_digest':'sha256:'+'2'*64,'capacity':{'max_concurrent_jobs':1,'active_jobs':0},'recovery':{'method':'reimage-from-pinned-snapshot','last_drill_at':'2026-08-01T00:00:00Z','maximum_recovery_minutes':120},'last_seen_at':'2026-08-08T03:59:30Z','quarantine_reason':None,'accepts_untrusted_jobs':True,'support_reference':'applecare-lab','decommissioned_at':None}]}

def self_test():
    good=fixture(); assert validate(good)==[]
    bad=json.loads(json.dumps(good)); bad['hosts'][0]['architecture']='x86_64'; bad['hosts'][0]['trust_tier']='untrusted'; assert {'hosts[0].architecture','hosts[0].trust_tier','hosts[0].untrusted_jobs_wrong_tier'}<=set(validate(bad))
    bad=fixture(); bad['hosts'][0].update(state='quarantined',quarantine_reason='',accepts_untrusted_jobs=True); assert {'hosts[0].quarantine_reason','hosts[0].quarantined_accepts_untrusted'}<=set(validate(bad))
    bad=fixture(); bad['hosts'][0]['last_seen_at']='2026-08-08T03:50:00Z'; assert 'hosts[0].schedulable_heartbeat_stale' in validate(bad)
    bad=fixture(); bad['hosts'][0]['trust_tier']='release-signing'; assert 'hosts[0].release_signing_accepts_untrusted' in validate(bad)
    bad=fixture(); bad['hosts'].append(json.loads(json.dumps(bad['hosts'][0]))); assert 'hosts[1].duplicate_host_id' in validate(bad)
    bad=fixture(); bad['hosts'][0]['profiles']=[{'name':'z-profile','digest':'sha256:'+'3'*64},{'name':'a-profile','digest':'sha256:'+'3'*64}]; out=set(validate(bad)); assert {'hosts[0].profiles.unsorted','hosts[0].profiles[1].duplicate_digest'}<=out
    bad=fixture(); bad['hosts'][0].update(state='busy'); bad['hosts'][0]['capacity']={'max_concurrent_jobs':1,'active_jobs':2}; assert 'hosts[0].capacity.exhausted' in validate(bad)
    bad=fixture(); bad['hosts'][0].update(state='revoked',accepts_untrusted_jobs=False); assert 'hosts[0].revoked_identity_active' in validate(bad)
    print('fleet inventory self-test: ok')

def main(argv=None):
    parser=argparse.ArgumentParser(); parser.add_argument('inventory',nargs='?',type=Path); parser.add_argument('--self-test',action='store_true'); args=parser.parse_args(argv)
    if args.self_test: self_test(); return 0
    if not args.inventory: parser.error('inventory required unless --self-test is used')
    errors=validate(json.loads(args.inventory.read_text())); json.dump({'ok':not errors,'errors':errors},sys.stdout,indent=2,sort_keys=True); print(); return 0 if not errors else 2
if __name__=='__main__': raise SystemExit(main())
