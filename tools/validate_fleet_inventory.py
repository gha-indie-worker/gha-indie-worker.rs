#!/usr/bin/env python3
import argparse,json,sys
from pathlib import Path
OS={'linux','macos','windows'}; ARCH={'x86_64','arm64'}; TRUST={'untrusted','trusted-release','signing'}; STATUS={'active','draining','maintenance','quarantined','offline','decommissioned'}; PATCH={'current','stale','emergency'}
def validate(data):
    errors=[]; hosts=data.get('hosts');
    if data.get('schema')!='gha-indie-worker.fleet-inventory/v1': errors.append('schema')
    if not isinstance(data.get('generated_at'),str) or not data['generated_at']: errors.append('generated_at')
    if not isinstance(hosts,list): return sorted(errors+['hosts'])
    seen=set()
    for index,host in enumerate(hosts):
        prefix=f'hosts[{index}]'
        if not isinstance(host,dict): errors.append(prefix); continue
        host_id=host.get('host_id')
        if not isinstance(host_id,str) or not host_id: errors.append(prefix+'.host_id')
        elif host_id in seen: errors.append(prefix+'.duplicate_host_id')
        else: seen.add(host_id)
        if not isinstance(host.get('owner'),str) or not host['owner']: errors.append(prefix+'.owner')
        if host.get('os') not in OS: errors.append(prefix+'.os')
        if host.get('architecture') not in ARCH: errors.append(prefix+'.architecture')
        if host.get('trust_tier') not in TRUST: errors.append(prefix+'.trust_tier')
        if host.get('status') not in STATUS: errors.append(prefix+'.status')
        if host.get('patch_state') not in PATCH: errors.append(prefix+'.patch_state')
        if host.get('status')=='active' and host.get('patch_state')!='current': errors.append(prefix+'.active_patch_state')
        profiles=host.get('profiles');
        if not isinstance(profiles,list) or not profiles or len(profiles)!=len(set(profiles)): errors.append(prefix+'.profiles')
        if not isinstance(host.get('image_generation'),str) or not host['image_generation']: errors.append(prefix+'.image_generation')
        if not isinstance(host.get('capacity',{}).get('max_concurrent_jobs'),int) or host['capacity']['max_concurrent_jobs']<1: errors.append(prefix+'.capacity')
        recovery=host.get('recovery',{})
        if not all(isinstance(recovery.get(k),str) and recovery[k] for k in ('method','last_drill_at')): errors.append(prefix+'.recovery')
        if host.get('status')=='quarantined' and not host.get('quarantine_reason'): errors.append(prefix+'.quarantine_reason')
        if host.get('trust_tier')=='signing' and host.get('accepts_untrusted_jobs') is not False: errors.append(prefix+'.signing_accepts_untrusted')
    return sorted(errors)
def fixture():
    return {'schema':'gha-indie-worker.fleet-inventory/v1','generated_at':'2026-08-08T00:00:00Z','hosts':[{'host_id':'mac-01','owner':'ci-ops','os':'macos','architecture':'arm64','trust_tier':'untrusted','status':'active','profiles':['macos-arm64-untrusted-v1'],'image_generation':'mac-2026-08-a','patch_state':'current','capacity':{'max_concurrent_jobs':1},'recovery':{'method':'reimage','last_drill_at':'2026-08-01T00:00:00Z'},'accepts_untrusted_jobs':True}]}
def main():
    p=argparse.ArgumentParser(); p.add_argument('inventory',nargs='?',type=Path); p.add_argument('--self-test',action='store_true'); a=p.parse_args()
    if a.self_test:
        good=fixture(); assert validate(good)==[]; bad=fixture(); bad['hosts'][0]['status']='quarantined'; bad['hosts'][0]['quarantine_reason']=''; assert 'hosts[0].quarantine_reason' in validate(bad); print('fleet inventory self-test: ok'); return 0
    if not a.inventory: p.error('inventory required')
    errors=validate(json.loads(a.inventory.read_text())); json.dump({'ok':not errors,'errors':errors},sys.stdout,sort_keys=True); print(); return 0 if not errors else 2
if __name__=='__main__': raise SystemExit(main())
