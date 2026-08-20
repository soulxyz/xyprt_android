#!/usr/bin/env python3
from pathlib import Path
import argparse,re,sys,json
p=argparse.ArgumentParser();p.add_argument('source');a=p.parse_args();root=Path(a.source)
allowed={'org.opencv.android.OpenCVLoader','org.opencv.android.Utils','org.opencv.core.Core','org.opencv.core.CvType','org.opencv.core.Mat','org.opencv.core.MatOfDouble','org.opencv.core.MatOfPoint','org.opencv.core.MatOfPoint2f','org.opencv.core.Point','org.opencv.core.Scalar','org.opencv.core.Size','org.opencv.core.TermCriteria','org.opencv.imgproc.Imgproc'}
imports=set(); offenders=[]
for f in root.rglob('*.kt'):
    text=f.read_text('utf-8',errors='ignore')
    for m in re.finditer(r'^import\s+(org\.opencv\.[A-Za-z0-9_.*]+)',text,re.M):
        imp=m.group(1);imports.add(imp)
        if imp not in allowed:offenders.append((str(f.relative_to(root)),imp))
print(json.dumps({'imports':sorted(imports),'offenders':offenders},indent=2))
if offenders:
    print('\nOpenCV usage escaped the frozen minimal r1 closure. Rebuild/validate a new runtime revision before merging.',file=sys.stderr);sys.exit(1)
