#!/usr/bin/env python3
import argparse,json,sys
from pathlib import Path
REQUIRED_CLEANUP={'keychain','provisioning-profiles','simulator-data','derived-data','package-caches','temp','background-processes','launch-items','workspace'}
REQUIRED_QUARANTINE={'cleanup-failure','integrity-failure','patch-stale','capability-drift','attestation-failure'}
def validate(profile):
    errors=[]
    def need(value,code):
        if not value: errors.append(code)
    need(profile.get('schema')=='gha-indie-worker.native-profile/v1','schema')
    need(profile.get('os')=='macos','os'); need(profile.get('architecture')=='arm64','architecture')
    need(profile.get('trust_tier')=='public-untrusted','trust_tier')
    user=profile.get('dedicated_user',{}); need(user.get('required') is True and user.get('administrator') is False,'dedicated_user')
    signing=profile.get('signing',{}); need(signing.get('identities_available') is False and signing.get('provisioning_profiles_available') is False,'signing_separation')
    need(profile.get('connectivity',{}).get('control_plane')=='outbound-only','connectivity')
    need(profile.get('isolation',{}).get('snapshot_required') is True,'snapshot')
    need(REQUIRED_CLEANUP <= set(profile.get('cleanup',[])),'cleanup')
    need(REQUIRED_QUARANTINE <= set(profile.get('quarantine_on',[])),'quarantine')
    need(profile.get('minimum_independent_slots_before_production',0)>=2,'capacity')
    return sorted(errors)
def main():
    p=argparse.ArgumentParser(); p.add_argument('profile',type=Path); a=p.parse_args(); errors=validate(json.loads(a.profile.read_text())); json.dump({'ok':not errors,'errors':errors},sys.stdout,sort_keys=True); print(); return 0 if not errors else 2
if __name__=='__main__': raise SystemExit(main())
