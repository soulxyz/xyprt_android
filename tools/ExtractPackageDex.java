import java.io.*;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;

public class ExtractPackageDex {
  public static void main(String[] args) throws Exception {
    if (args.length != 3) throw new IllegalArgumentException("usage: input.dex prefix output.dex");
    DexBackedDexFile dex = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.getDefault());
    DexPool pool = new DexPool(dex.getOpcodes());
    int n=0;
    for (ClassDef c: dex.getClasses()) {
      if (c.getType().startsWith(args[1])) { pool.internClass(c); n++; }
    }
    FileDataStore out = new FileDataStore(new File(args[2]));
    try { pool.writeTo(out); } finally { out.close(); }
    System.out.println("classes="+n+" out="+args[2]);
  }
}
