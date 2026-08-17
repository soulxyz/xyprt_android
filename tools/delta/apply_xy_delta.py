#!/usr/bin/env python3
import gzip,hashlib,struct,sys
from pathlib import Path
MAGIC=b"XYDLTA1\0"
def exact(f,n):
    b=f.read(n)
    if len(b)!=n: raise ValueError("truncated")
    return b
def apply(oldp,patchp,outp):
    old=Path(oldp).read_bytes()
    with gzip.open(patchp,'rb') as f:
        if exact(f,8)!=MAGIC or struct.unpack('>i',exact(f,4))[0]!=1: raise ValueError('format')
        osz=struct.unpack('>q',exact(f,8))[0];osha=exact(f,32);nsz=struct.unpack('>q',exact(f,8))[0];nsha=exact(f,32);cnt=struct.unpack('>i',exact(f,4))[0]
        if osz!=len(old) or hashlib.sha256(old).digest()!=osha: raise ValueError('old mismatch')
        out=bytearray()
        for _ in range(cnt):
            t=exact(f,1)[0]
            if t==0:
                off,l=struct.unpack('>qI',exact(f,12));out.extend(old[off:off+l])
            elif t==1:
                l=struct.unpack('>I',exact(f,4))[0];out.extend(exact(f,l))
            else: raise ValueError('op')
    if len(out)!=nsz or hashlib.sha256(out).digest()!=nsha: raise ValueError('new mismatch')
    Path(outp).write_bytes(out)
if __name__=='__main__':apply(*sys.argv[1:4])
