#!/usr/bin/env python3
"""Generate an XYDLTA1 delta patch between two already-signed APKs.

The patch rebuilds the *exact* target APK byte-for-byte. It never patches an installed package in
place: the Android client reconstructs a normal APK, verifies SHA-256/package/version/signing, then
hands that APK to PackageInstaller.
"""
from __future__ import annotations
import argparse, gzip, hashlib, os, struct
from pathlib import Path

MAGIC=b"XYDLTA1\0"
MIN_CHUNK=2048
AVG_BITS=13       # ~8 KiB average boundary
MAX_CHUNK=65536
MASK=(1<<AVG_BITS)-1

# Deterministic 64-bit gear table. Values are generated rather than copied from a third-party lib.
def make_gear():
    x=0x9E3779B97F4A7C15
    out=[]
    for _ in range(256):
        x ^= (x << 13) & 0xFFFFFFFFFFFFFFFF
        x ^= x >> 7
        x ^= (x << 17) & 0xFFFFFFFFFFFFFFFF
        out.append(x & 0xFFFFFFFFFFFFFFFF)
    return out
GEAR=make_gear()

def chunks(data: bytes):
    start=0; h=0; n=len(data)
    for i,b in enumerate(data):
        h=((h<<1)+GEAR[b]) & 0xFFFFFFFFFFFFFFFF
        size=i-start+1
        if size >= MIN_CHUNK and ((h & MASK)==0 or size >= MAX_CHUNK):
            yield start, i+1
            start=i+1; h=0
    if start<n: yield start,n

def digest32(data: bytes)->bytes:
    return hashlib.sha256(data).digest()

def digest16(data: bytes)->bytes:
    return hashlib.blake2b(data,digest_size=16).digest()

def merge_ops(ops):
    merged=[]
    for op in ops:
        if not merged: merged.append(op); continue
        prev=merged[-1]
        if op[0]=="copy" and prev[0]=="copy" and prev[1]+prev[2]==op[1]:
            merged[-1]=("copy",prev[1],prev[2]+op[2])
        elif op[0]=="data" and prev[0]=="data":
            merged[-1]=("data",prev[1]+op[1])
        else: merged.append(op)
    return merged

def build(old: bytes,new: bytes):
    index={}
    for a,b in chunks(old):
        blob=old[a:b]
        index.setdefault(digest16(blob),[]).append((a,b-a))
    ops=[]; copied=0
    for a,b in chunks(new):
        blob=new[a:b]; match=None
        for pos,length in index.get(digest16(blob),()):
            if length==len(blob) and old[pos:pos+length]==blob:
                match=(pos,length);break
        if match:
            ops.append(("copy",match[0],match[1])); copied+=match[1]
        else:
            ops.append(("data",blob))
    return merge_ops(ops),copied

def write_patch(old_path:Path,new_path:Path,out_path:Path):
    old=old_path.read_bytes();new=new_path.read_bytes();ops,copied=build(old,new)
    out_path.parent.mkdir(parents=True,exist_ok=True)
    with open(out_path,"wb") as raw:
        with gzip.GzipFile(filename="",fileobj=raw,mode="wb",compresslevel=9,mtime=0) as f:
            f.write(MAGIC);f.write(struct.pack(">i",1));f.write(struct.pack(">q",len(old)));f.write(digest32(old))
            f.write(struct.pack(">q",len(new)));f.write(digest32(new));f.write(struct.pack(">i",len(ops)))
            for op in ops:
                if op[0]=="copy":
                    _,off,length=op;f.write(b"\x00");f.write(struct.pack(">qI",off,length))
                else:
                    blob=op[1];f.write(b"\x01");f.write(struct.pack(">I",len(blob)));f.write(blob)
    return {
      "old_size":len(old),"new_size":len(new),"patch_size":out_path.stat().st_size,
      "old_sha256":hashlib.sha256(old).hexdigest(),"new_sha256":hashlib.sha256(new).hexdigest(),
      "patch_sha256":hashlib.sha256(out_path.read_bytes()).hexdigest(),"copied":copied,"ops":len(ops)
    }

def main():
    ap=argparse.ArgumentParser();ap.add_argument("old");ap.add_argument("new");ap.add_argument("output")
    a=ap.parse_args();r=write_patch(Path(a.old),Path(a.new),Path(a.output))
    for k,v in r.items(): print(f"{k}={v}")
    print(f"ratio={r['patch_size']/r['new_size']:.4f}")
if __name__=="__main__":main()
