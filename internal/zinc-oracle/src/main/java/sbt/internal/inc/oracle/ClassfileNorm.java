/*
 * Zinc - The incremental compiler for Scala.
 * Copyright Scala Center, Lightbend, and Mark Harrah
 *
 * Licensed under Apache License 2.0
 * SPDX-License-Identifier: Apache-2.0
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package sbt.internal.inc.oracle;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureWriter;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TextifierSupport;
import org.objectweb.asm.util.TraceClassVisitor;

/**
 * Compares classfiles through a ladder of normalisations, to say how two classfiles differ rather
 * than only that they do. scalac's output varies with the compilation batch in ways that mean
 * nothing to a JVM: the order of the constant pool, the fresh names of synthetic locals, whether a
 * type variable is called {@code A} or {@code A$}. Each {@link Level} ignores one more of these.
 *
 * <p>The normalised digests are taken from a rendering of the class as text that has no
 * constant-pool indices in it. Instructions, operands, line numbers, frames, exception tables,
 * annotations, inner classes, flags and the order of members are all in that rendering, so a
 * difference in any of them is a difference at every level.
 *
 * <p>Attributes that ASM doesn't know are rendered as their bytes, except two. {@code
 * ScalaInlineInfo} holds constant-pool indices, so it is decoded and its references rendered as
 * the strings they point at. {@code TASTY}, the UUID of a Scala 3 class's {@code .tasty} file, is
 * left out of the normalised digests and reported as {@link Digests#tastyUuid}, so that a caller
 * can compare the {@code .tasty} files instead.
 */
public final class ClassfileNorm {
  private ClassfileNorm() {}

  /** What a comparison ignores. Each level ignores what the ones before it do, and one more thing. */
  public enum Level {
    /** Nothing: the bytes are equal. */
    RAW("raw"),

    /** The order of the constant pool, and so the indices into it. */
    POOL("pool"),

    /**
     * And the names in {@code LocalVariableTable} and {@code LocalVariableTypeTable}. Slots,
     * descriptors, ranges and the code that uses them still count.
     */
    LOCALS("locals"),

    /**
     * And trailing {@code $}s on the names of type variables in generic signatures: scalac names a
     * type variable {@code A} or {@code A$} depending on the batch (scala/scala#11289, #11319).
     * Type variables are not otherwise renamed, so {@code A} and {@code B} still differ.
     */
    TYPE_VARS("typevars");

    private final String label;

    Level(String label) {
      this.label = label;
    }

    /** The level's name in reports: {@code raw}, {@code pool}, {@code locals}, {@code typevars}. */
    public String label() {
      return label;
    }
  }

  /**
   * A classfile's digest at each {@link Level}, as 16 hex digits of SHA-256, and the UUID in its
   * {@code TASTY} attribute if it has one. The UUID is part of {@code raw} only.
   */
  public record Digests(
      String raw, String pool, String locals, String typeVars, Optional<String> tastyUuid) {
    public String at(Level level) {
      return switch (level) {
        case RAW -> raw;
        case POOL -> pool;
        case LOCALS -> locals;
        case TYPE_VARS -> typeVars;
      };
    }
  }

  public static Digests digests(byte[] bytes) {
    Rendering pool = render(bytes, true, true, false);
    Rendering locals = render(bytes, false, true, false);
    Rendering typeVars = render(bytes, false, false, false);
    return new Digests(
        sha(bytes), sha(pool.text), sha(locals.text), sha(typeVars.text), pool.tastyUuid);
  }

  /**
   * The lowest level at which the two are equal, or empty if they differ at every level. {@code
   * tastyUuid} is not compared above {@link Level#RAW}.
   */
  public static Optional<Level> level(Digests a, Digests b) {
    return Arrays.stream(Level.values()).filter(l -> a.at(l).equals(b.at(l))).findFirst();
  }

  /**
   * The class as text, for a reader: what {@link Level#POOL} compares, and the {@code TASTY}
   * attribute. It has no constant-pool indices in it.
   */
  public static String text(byte[] bytes) {
    return render(bytes, true, true, true).text;
  }

  /** {@link #diff(byte[], byte[], int)} of at most 40 lines. */
  public static List<String> diff(byte[] a, byte[] b) {
    return diff(a, b, 40);
  }

  /**
   * The lines of {@link #text} that differ, those of {@code a} after {@code -} and those of {@code
   * b} after {@code +}, {@code max} of them at most.
   */
  public static List<String> diff(byte[] a, byte[] b, int max) {
    List<String> lines = lineDiff(text(a).lines().toList(), text(b).lines().toList());
    return lines.size() > max ? List.copyOf(lines.subList(0, Math.max(max, 0))) : lines;
  }

  /** {@code signature} without the trailing {@code $}s of its type variables' names. */
  public static String stripTypeVarSuffix(String signature, boolean isType) {
    SignatureWriter writer =
        new SignatureWriter() {
          @Override
          public void visitFormalTypeParameter(String name) {
            super.visitFormalTypeParameter(strip(name));
          }

          @Override
          public void visitTypeVariable(String name) {
            super.visitTypeVariable(strip(name));
          }
        };
    try {
      SignatureReader reader = new SignatureReader(signature);
      if (isType) reader.acceptType(writer);
      else reader.accept(writer);
      return writer.toString();
    } catch (RuntimeException e) {
      return signature;
    }
  }

  private static String strip(String name) {
    int end = name.length();
    while (end > 0 && name.charAt(end - 1) == '$') end--;
    return end == 0 ? name : name.substring(0, end);
  }

  /**
   * The lines that differ between {@code a} and {@code b}, in order: those only in {@code a} after
   * {@code -} and those only in {@code b} after {@code +}.
   */
  public static List<String> lineDiff(List<String> a, List<String> b) {
    int prefix = 0;
    while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) prefix++;
    int suffix = 0;
    while (suffix < a.size() - prefix
        && suffix < b.size() - prefix
        && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) suffix++;
    List<String> x = a.subList(prefix, a.size() - suffix);
    List<String> y = b.subList(prefix, b.size() - suffix);
    List<String> out = new ArrayList<>();
    if ((long) x.size() * y.size() > MAX_LCS_CELLS) {
      for (String line : x) out.add("-" + line);
      for (String line : y) out.add("+" + line);
      return out;
    }
    int[][] lcs = new int[x.size() + 1][y.size() + 1];
    for (int i = x.size() - 1; i >= 0; i--)
      for (int j = y.size() - 1; j >= 0; j--)
        lcs[i][j] =
            x.get(i).equals(y.get(j))
                ? lcs[i + 1][j + 1] + 1
                : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    int i = 0;
    int j = 0;
    while (i < x.size() || j < y.size()) {
      if (i < x.size() && j < y.size() && x.get(i).equals(y.get(j))) {
        i++;
        j++;
      } else if (j == y.size() || (i < x.size() && lcs[i + 1][j] >= lcs[i][j + 1])) {
        out.add("-" + x.get(i));
        i++;
      } else {
        out.add("+" + y.get(j));
        j++;
      }
    }
    return out;
  }

  private static final long MAX_LCS_CELLS = 4000000L;
  private static final String INLINE_INFO = "ScalaInlineInfo";
  private static final String TASTY_ATTRIBUTE = "TASTY";

  static String sha(byte[] bytes) {
    try {
      return hex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String sha(String text) {
    return sha(text.getBytes(StandardCharsets.UTF_8));
  }

  static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }

  private record Rendering(String text, Optional<String> tastyUuid) {}

  private static Rendering render(
      byte[] bytes, boolean localNames, boolean typeVarSuffix, boolean tasty) {
    Text printer = new Text();
    Canon canon =
        new Canon(new TraceClassVisitor(null, printer, null), localNames, typeVarSuffix, tasty);
    new ClassReader(bytes).accept(canon, new Attribute[] {new InlineInfoPrototype()}, 0);
    StringWriter out = new StringWriter();
    PrintWriter writer = new PrintWriter(out);
    printer.print(writer);
    writer.flush();
    return new Rendering(out.toString(), canon.tastyUuid);
  }

  /** An attribute whose content is already text with no constant-pool indices in it. */
  private static final class Rendered extends Attribute implements TextifierSupport {
    private final String content;

    Rendered(String name, String content) {
      super(name);
      this.content = content;
    }

    @Override
    public void textify(StringBuilder out, Map<Label, String> labelNames) {
      out.append(" : ").append(content).append('\n');
    }
  }

  private static Rendered rendered(Attribute attribute) {
    return attribute instanceof Rendered r
        ? r
        : new Rendered(attribute.type, hex(contentOf(attribute)));
  }

  private static byte[] contentOf(Attribute attribute) {
    return Attribute.write(attribute, new ClassWriter(0), null, 0, -1, -1);
  }

  /**
   * Reads scalac's {@code ScalaInlineInfo}, whose strings are references to the constant pool,
   * into text that has the strings themselves. A version it doesn't know stays as bytes.
   */
  private static final class InlineInfoPrototype extends Attribute {
    InlineInfoPrototype() {
      super(INLINE_INFO);
    }

    @Override
    protected Attribute read(
        ClassReader reader,
        int offset,
        int length,
        char[] buffer,
        int codeOffset,
        Label[] labels) {
      String content;
      if (length == 0 || reader.readByte(offset) != 1) content = raw(reader, offset, length);
      else {
        try {
          content = new Decoder(reader, offset + 1, buffer).decode(offset + length);
        } catch (RuntimeException e) {
          content = raw(reader, offset, length);
        }
      }
      return new Rendered(INLINE_INFO, content);
    }

    private static String raw(ClassReader reader, int offset, int length) {
      byte[] bytes = new byte[length];
      for (int i = 0; i < length; i++) bytes[i] = (byte) reader.readByte(offset + i);
      return hex(bytes);
    }
  }

  /** Reads the body of a version 1 {@code ScalaInlineInfo}, from after its version byte. */
  private static final class Decoder {
    private final ClassReader reader;
    private final char[] buffer;
    private int next;

    Decoder(ClassReader reader, int start, char[] buffer) {
      this.reader = reader;
      this.next = start;
      this.buffer = buffer;
    }

    private int readByte() {
      int r = reader.readByte(next);
      next += 1;
      return r;
    }

    private int readShort() {
      int r = reader.readUnsignedShort(next);
      next += 2;
      return r;
    }

    private String utf8() {
      String r = reader.readUTF8(next, buffer);
      next += 2;
      return r;
    }

    String decode(int end) {
      StringBuilder out = new StringBuilder();
      int flags = readByte();
      out.append("version 1, flags ").append(flags);
      if ((flags & 2) != 0) out.append(", self ").append(utf8());
      if ((flags & 4) != 0) out.append(", sam ").append(utf8()).append(utf8());
      int methods = readShort();
      for (int i = 0; i < methods; i++)
        out.append("\n    ").append(utf8()).append(utf8()).append(' ').append(readByte());
      if (next != end) throw new IllegalArgumentException("trailing bytes");
      return out.toString();
    }
  }

  /**
   * Passes a class on to {@code next} with its unknown attributes {@link #rendered}, and without
   * what the level being computed ignores.
   */
  private static final class Canon extends ClassVisitor {
    private final boolean localNames;
    private final boolean typeVarSuffix;
    private final boolean tasty;
    Optional<String> tastyUuid = Optional.empty();

    Canon(ClassVisitor next, boolean localNames, boolean typeVarSuffix, boolean tasty) {
      super(Opcodes.ASM9, next);
      this.localNames = localNames;
      this.typeVarSuffix = typeVarSuffix;
      this.tasty = tasty;
    }

    private String sig(String signature, boolean isType) {
      return typeVarSuffix || signature == null ? signature : stripTypeVarSuffix(signature, isType);
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {
      super.visit(version, access, name, sig(signature, false), superName, interfaces);
    }

    @Override
    public void visitAttribute(Attribute attribute) {
      if (attribute.type.equals(TASTY_ATTRIBUTE)) {
        tastyUuid = Optional.of(hex(contentOf(attribute)));
        if (tasty) super.visitAttribute(rendered(attribute));
      } else super.visitAttribute(rendered(attribute));
    }

    @Override
    public RecordComponentVisitor visitRecordComponent(
        String name, String descriptor, String signature) {
      RecordComponentVisitor to =
          super.visitRecordComponent(name, descriptor, sig(signature, true));
      return new RecordComponentVisitor(Opcodes.ASM9, to) {
        @Override
        public void visitAttribute(Attribute attribute) {
          super.visitAttribute(rendered(attribute));
        }
      };
    }

    @Override
    public FieldVisitor visitField(
        int access, String name, String descriptor, String signature, Object value) {
      FieldVisitor to = super.visitField(access, name, descriptor, sig(signature, true), value);
      return new FieldVisitor(Opcodes.ASM9, to) {
        @Override
        public void visitAttribute(Attribute attribute) {
          super.visitAttribute(rendered(attribute));
        }
      };
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      MethodVisitor to =
          super.visitMethod(access, name, descriptor, sig(signature, false), exceptions);
      return new MethodVisitor(Opcodes.ASM9, to) {
        @Override
        public void visitAttribute(Attribute attribute) {
          super.visitAttribute(rendered(attribute));
        }

        @Override
        public void visitLocalVariable(
            String name, String descriptor, String signature, Label start, Label end, int index) {
          super.visitLocalVariable(
              localNames ? name : "_", descriptor, sig(signature, true), start, end, index);
        }
      };
    }
  }

  /**
   * A {@code Textifier} that also says which kind of number a constant is: {@code LDC 1} is
   * otherwise the same line for an {@code Integer} and a {@code Long}.
   */
  private static final class Text extends Textifier {
    Text() {
      super(Opcodes.ASM9);
    }

    @Override
    protected Textifier createTextifier() {
      return new Text();
    }

    @Override
    public void visitLdcInsn(Object value) {
      super.visitLdcInsn(value);
      kinds(value);
    }

    @Override
    public void visitInvokeDynamicInsn(
        String name, String descriptor, Handle bootstrap, Object... arguments) {
      super.visitInvokeDynamicInsn(name, descriptor, bootstrap, arguments);
      kinds(arguments);
    }

    private void kinds(Object... values) {
      if (Arrays.stream(values).anyMatch(v -> v instanceof Number))
        text.add(
            Arrays.stream(values)
                .map(v -> v.getClass().getSimpleName())
                .collect(Collectors.joining(", ", tab2 + "// constants: ", "\n")));
    }
  }
}
