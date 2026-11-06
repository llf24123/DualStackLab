package com.dualstack.lab;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Xml;
import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import com.reandroid.apk.ApkModule;
import com.reandroid.archive.BlockInputSource;
import com.reandroid.archive.InputSource;
import com.reandroid.archive.writer.*;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.*;
import com.reandroid.json.JSONObject;
import org.jf.baksmali.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.smali.*;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.nio.file.Files;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import javax.security.auth.x500.X500Principal;

final class ApkTools {
    // ARSCLib shares charset decoders globally; resource operations must serialize.
    static synchronized String xmlText(File apk, String entry) throws IOException {
        try (ApkModule module = ApkModule.loadApkFile(apk)) {
            ResXmlDocument document = module.loadResXmlDocument(entry);
            if (document == null) throw new IOException("不是 Android 二进制 XML");
            return document.serializeToXml();
        }
    }
    static synchronized void saveXml(File apk, String entry, String text) throws Exception {
        try (ApkModule module = ApkModule.loadApkFile(apk)) {
            ResXmlDocument old = module.loadResXmlDocument(entry);
            ResXmlDocument document = entry.equals("AndroidManifest.xml") ? new AndroidManifestBlock() : new ResXmlDocument();
            document.setApkFile(module); document.setPackageBlock(old.getPackageBlock());
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            parser.setInput(new StringReader(text)); document.parse(parser); document.refreshFull();
            if (document instanceof AndroidManifestBlock) module.setManifest((AndroidManifestBlock) document);
            else module.add(new BlockInputSource<>(entry, document));
            writeChanged(module, apk);
        }
    }
    static synchronized String arscText(File apk, String entry) throws IOException {
        try (ApkModule module = ApkModule.loadApkFile(apk)) {
            TableBlock table = module.getTableBlock();
            if (table == null) throw new IOException("APK 没有资源表");
            return table.toJson().toString(2);
        }
    }
    static synchronized String arscFileText(File file) throws IOException { return TableBlock.load(file).toJson().toString(2); }
    static synchronized void saveArscFile(File output, String json) throws IOException {
        TableBlock table = new TableBlock(); table.fromJson(new JSONObject(json)); table.refreshFull(); table.writeBytes(output);
    }
    static synchronized void saveArsc(File apk, String entry, String json) throws IOException {
        try (ApkModule module = ApkModule.loadApkFile(apk)) {
            TableBlock table = new TableBlock(); table.fromJson(new JSONObject(json)); table.refreshFull();
            module.setTableBlock(table); writeChanged(module, apk);
        }
    }
    private static void clearSignatures(ApkModule module) {
        module.setApkSignatureBlock(null);
        for (InputSource in : module.getInputSources()) if (ArchiveOps.isSignature(in.getAlias()))
            module.removeInputSource(in.getAlias());
    }
    private static void alignedWrite(ApkModule module, File output) throws IOException {
        clearSignatures(module); module.updateUncompressedFiles();
        ZipAligner align = new ZipAligner(); align.setDefaultAlignment(4);
        align.setFileAlignment(ZipAligner.PREDICATE_NATIVE_LIBS, 16384);
        try (ApkFileWriter writer = module.createApkFileWriter(output)) {
            writer.setZipAligner(align); writer.write();
        }
    }
    private static void writeChanged(ApkModule module, File source) throws IOException {
        FileStore.FileState before = FileStore.FileState.read(source);
        File tmp = File.createTempFile(".apk-edit-", ".apk", source.getParentFile());
        try { alignedWrite(module, tmp); FileStore.publish(tmp, source, before); ArchiveOps.forget(source); }
        finally { Files.deleteIfExists(tmp.toPath()); }
    }
    static synchronized void align(File source, File output) throws IOException {
        try (ApkModule module = ApkModule.loadApkFile(source)) { alignedWrite(module, output); }
    }
    static synchronized String packageName(File source) throws IOException {
        try (ApkModule module = ApkModule.loadApkFile(source)) { return module.getPackageName(); }
    }
    static synchronized void clonePackage(File source, File output, String packageName) throws Exception {
        if (!packageName.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))
            throw new IOException("包名格式无效");
        try (ApkModule module = ApkModule.loadApkFile(source)) {
            AndroidManifestBlock manifest = module.getAndroidManifest();
            String oldName = manifest.getPackageName();
            if (oldName.equals(packageName)) throw new IOException("请输入不同的包名");
            if (manifest.isSplit()) throw new IOException("共存制作需完整 APK，当前文件是 split APK");
            // Keep component class references pointed at the existing DEX classes.
            manifest.ensureFullClassNames();
            Iterator<ResXmlElement> all = manifest.recursiveElements();
            while (all.hasNext()) {
                ResXmlElement element = all.next();
                Iterator<ResXmlAttribute> attrs = element.getAttributes();
                while (attrs.hasNext()) {
                    ResXmlAttribute attribute = attrs.next();
                    String name = attribute.getName(), value = attribute.getValueAsString();
                    if (value == null) continue;
                    if (name.equals("authorities")) {
                        String[] authorities = value.split(";");
                        for (int i = 0; i < authorities.length; i++)
                            authorities[i] = authorities[i].startsWith(oldName) ?
                                    packageName + authorities[i].substring(oldName.length()) : packageName + "." + authorities[i];
                        attribute.setValueAsString(String.join(";", authorities));
                    } else if ((element.getName().equals("permission") && name.equals("name"))
                            || name.equals("permission") || name.equals("readPermission") || name.equals("writePermission")
                            || name.equals("taskAffinity")) {
                        if (value.startsWith(oldName)) attribute.setValueAsString(packageName + value.substring(oldName.length()));
                    }
                }
            }
            module.setPackageName(packageName); module.refreshManifest(); alignedWrite(module, output);
        }
    }
    static void disassemble(File dex, File folder) throws IOException {
        if (folder.exists() && folder.list() != null && folder.list().length > 0)
            throw new IOException("工作区已有内容，请另选空目录");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("无法创建工作区");
        BaksmaliOptions options = new BaksmaliOptions(); options.apiLevel = 28;
        options.debugInfo = true; options.localsDirective = true; options.parameterRegisters = true;
        if (!Baksmali.disassembleDexFile(DexFileFactory.loadDexFile(dex, Opcodes.getDefault()),
                folder, 2, options)) throw new IOException("DEX 反汇编失败");
    }
    static void assemble(File folder, File dex) throws IOException {
        if (!folder.isDirectory()) throw new IOException("请先反汇编 DEX");
        SmaliOptions options = new SmaliOptions(); options.apiLevel = 28;
        options.outputDexFile = dex.getAbsolutePath(); options.jobs = 2; options.verboseErrors = true;
        if (!Smali.assemble(options, folder.getAbsolutePath())) throw new IOException("Smali 编译失败，请检查语法");
        DexFileFactory.loadDexFile(dex, Opcodes.getDefault());
    }
    static void saveDexClass(File archive, String entry, String descriptor, String text, File cache) throws IOException {
        FileStore.FileState before = FileStore.FileState.read(archive);
        File folder = Files.createTempDirectory(cache.toPath(), "smali-edit-").toFile();
        File original = entry.isEmpty() ? archive : new File(folder, "original.dex");
        File compiled = new File(folder, "class.dex"), merged = new File(folder, "merged.dex");
        File input = new File(folder, "input"); input.mkdir();
        try {
            if (!entry.isEmpty()) ArchiveOps.extractOne(archive, entry, original);
            DexFile old = DexFileFactory.loadDexFile(original, Opcodes.getDefault());
            Files.write(new File(input, "Class.smali").toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assemble(input, compiled);
            DexFile patch = DexFileFactory.loadDexFile(compiled, old.getOpcodes());
            if (patch.getClasses().size() != 1) throw new IOException("单类编辑必须保留一个类");
            ClassDef changed = patch.getClasses().iterator().next();
            if (!descriptor.equals(changed.getType())) throw new IOException("单类编辑不能修改类描述符");
            List<ClassDef> classes = new ArrayList<>();
            boolean found = false;
            for (ClassDef def : old.getClasses()) {
                if (descriptor.equals(def.getType())) { classes.add(changed); found = true; }
                else classes.add(def);
            }
            if (!found) throw new IOException("原 DEX 中没有此类");
            DexFileFactory.writeDexFile(merged.getAbsolutePath(), new DexFile() {
                public Set<? extends ClassDef> getClasses() { return new LinkedHashSet<>(classes); }
                public Opcodes getOpcodes() { return old.getOpcodes(); }
            });
            DexFileFactory.loadDexFile(merged, old.getOpcodes());
            if (entry.isEmpty()) {
                File temporary = File.createTempFile(".dex-edit-", ".dex", archive.getParentFile());
                try {
                    Files.copy(merged.toPath(), temporary.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    FileStore.publish(temporary, archive, before);
                } finally { Files.deleteIfExists(temporary.toPath()); }
            } else ArchiveOps.rewrite(archive, null, null, Map.of(entry, merged));
        } finally { removeWork(folder); }
    }
    static void disassembleClass(File archive, String entry, String descriptor, File output) throws IOException {
        File folder = Files.createTempDirectory(output.getParentFile().toPath(), "class-rebuild-").toFile();
        try {
            File dex = archive;
            if (!entry.isEmpty()) { dex = new File(folder, "source.dex"); ArchiveOps.extractOne(archive, entry, dex); }
            File smali = new File(folder, "smali");
            BaksmaliOptions options = new BaksmaliOptions(); options.apiLevel = 28; options.debugInfo = true;
            if (!Baksmali.disassembleDexFile(DexFileFactory.loadDexFile(dex, Opcodes.getDefault()),
                    smali, 1, options, List.of(descriptor))) throw new IOException("类反汇编失败");
            File found = firstSmali(smali);
            if (found == null) throw new IOException("原 DEX 中没有此类");
            Files.copy(found.toPath(), output.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { removeWork(folder); }
    }
    private static File firstSmali(File directory) {
        File[] children = directory.listFiles();
        if (children == null) return null;
        for (File child : children) {
            File found = child.isDirectory() ? firstSmali(child) : child.getName().endsWith(".smali") ? child : null;
            if (found != null) return found;
        }
        return null;
    }
    private static void removeWork(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) removeWork(child);
        Files.deleteIfExists(file.toPath());
    }
    static void sign(Context context, File source, File output) throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        String alias = "DualStackResearchSigner";
        if (!keys.containsAlias(alias)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            Calendar until = Calendar.getInstance(); until.add(Calendar.YEAR, 25);
            generator.initialize(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(new X500Principal("CN=DualStack Research"))
                    .setCertificateSerialNumber(java.math.BigInteger.ONE)
                    .setCertificateNotBefore(new Date(System.currentTimeMillis() - 86400000))
                    .setCertificateNotAfter(until.getTime()).build());
            generator.generateKeyPair();
        }
        PrivateKey key = (PrivateKey) keys.getKey(alias, null);
        X509Certificate certificate = (X509Certificate) keys.getCertificate(alias);
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder("DualStack", key, List.of(certificate)).build();
        new ApkSigner.Builder(List.of(signer)).setInputApk(source).setOutputApk(output).setMinSdkVersion(26)
                .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true).setV4SigningEnabled(false)
                .setOtherSignersSignaturesPreserved(false).build().sign();
        ApkVerifier.Result result = new ApkVerifier.Builder(output).setMinCheckedPlatformVersion(26).build().verify();
        if (!result.isVerified()) throw new IOException("签名验证失败：" + result.getErrors());
    }
    static String verify(File apk) throws Exception {
        ApkVerifier.Result result = new ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(26).build().verify();
        return "签名验证：" + (result.isVerified() ? "通过" : "失败") + "\n"
                + "v1: " + result.isVerifiedUsingV1Scheme() + "\nv2: " + result.isVerifiedUsingV2Scheme()
                + "\nv3: " + result.isVerifiedUsingV3Scheme() + "\n" + result.getErrors() + "\n" + result.getWarnings();
    }
    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[131072]; int n;
            while ((n = in.read(bytes)) >= 0) digest.update(bytes, 0, n);
        }
        StringBuilder text = new StringBuilder(); for (byte b : digest.digest()) text.append(String.format("%02x", b & 255));
        return text.toString();
    }
    private ApkTools() {}
}
