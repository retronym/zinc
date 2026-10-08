#!/usr/bin/env python3
"""Hand-written program spaces for the Conformance harness, beyond the Lean model.

Each space is a set of factors and a function from a cfg (one value per factor) to source
files, each with a tier (0: the `macros` subproject, 1: upstream in the split layout, 2:
downstream). A base is a cfg; its edits change one factor. Factors listed as `fixed` describe
the build's shape and are not edited. The model's verdict is unknown for these spaces, so the
clean build is the only oracle.

    bin/conformance-spaces.py SPACE [--max-bases N] > cases.jsonl
    bin/conformance-spaces.py --groups flat.jsonl > flat-groups.jsonl

Bases are emitted in digit-reversed mixed-radix order, so a prefix spreads over every factor.
"""

import itertools
import json
import sys

PKG = "package conf\n\n"


def default(ty):
    return {"Int": "1", "String": '""', "Long": "1L"}.get(ty, f"null.asInstanceOf[{ty}]")


# --- trait fields, private members and super calls -----------------------------------------

def fields(c):
    files = {}
    m = c["mMem"]
    body = {
        "none": "",
        "def": "  def m: Int = 1\n",
        "val": "  val m: Int = 1\n",
        "var": "  var m: Int = 1\n",
        "lazy": "  lazy val m: Int = 1\n",
        "valStr": '  val m: String = ""\n',
        "privVal": "  private val p: Int = 1\n  def m: Int = p\n",
        "privVar": "  private var p: Int = 1\n  def m: Int = { p += 1; p }\n",
        "privDef": "  private def p: Int = 1\n  def m: Int = p\n",
        "privLazy": "  private lazy val p: Int = 1\n  def m: Int = p\n",
        "objVal": "  object o { val v = 1 }\n  def m: Int = o.v\n",
    }[m]
    files["M.scala"] = (1, PKG + f"trait M {{\n{body}  def k: Int = 0\n}}\n")
    n = {
        "none": "",
        "super": "  override def m: Int = super.m + 1\n",
        "superK": "  override def k: Int = super.k + 1\n",
        "abstractOverride": "  abstract override def m: Int = super.m + 1\n",
    }[c["nBody"]]
    files["N.scala"] = (1, PKG + f"trait N extends M {{\n{n}}}\n")
    b = {"none": "", "def": "  def m: Int = 2\n", "val": "  val m: Int = 2\n"}[c["bMem"]]
    files["B.scala"] = (2, PKG + f"class B {{\n{b}}}\n")
    mix = {"M": " with M", "N": " with N", "MN": " with M with N", "NM": " with N with M"}[c["cMix"]]
    cbody = {"none": "", "override": "  override def m: Int = 3\n"}[c["cMem"]]
    files["C.scala"] = (2, PKG + f"class C extends B{mix} {{\n{cbody}}}\n")
    files["D.scala"] = (2, PKG + f"class D extends C\n")
    x = {
        "C": "  def f(c: C) = c.m\n",
        "M": "  def f(c: M) = c.m\n",
        "D": "  def f(d: D) = d.m\n",
    }[c["xUse"]]
    files["X.scala"] = (2, PKG + f"object X {{\n{x}  def g = new D\n}}\n")
    return files


FIELDS = dict(
    factors=dict(
        mMem=["none", "def", "val", "var", "lazy", "valStr", "privVal", "privVar", "privDef",
              "privLazy", "objVal"],
        nBody=["none", "super", "superK", "abstractOverride"],
        bMem=["none", "def", "val"],
        cMix=["M", "N", "MN", "NM"],
        cMem=["none", "override"],
        xUse=["C", "M", "D"],
    ),
    render=fields,
)


# --- value classes and erasure ---------------------------------------------------------------

def valueclass(c):
    files = {}
    u = c["vUnder"]
    ext = " extends AnyVal" if c["vKind"] == "anyval" else ""
    files["V.scala"] = (1, PKG +
        f"class V(val u: {u}){ext}\nobject V {{ def mk: V = new V({default(u)}) }}\n")
    files["M.scala"] = (1, PKG + "trait M[T] { def m: T }\n")
    par = {"none": "", "MV": " extends M[V]"}[c["aGen"]]
    am = {"V": "  def m: V = V.mk\n", "Int": "  def m: Int = 1\n", "none": ""}[c["aRes"]]
    ap = {"none": "", "V": "  def p(v: V): Int = 1\n", "Vs": "  def p(v: V): Int = 1\n  def p(i: Int): Int = 2\n"}[c["aParam"]]
    files["A.scala"] = (1, PKG + f"abstract class A{par} {{\n{am}{ap}}}\n")
    bo = {"none": "", "m": "  override def m: V = V.mk\n", "p": "  override def p(v: V): Int = 3\n"}[c["bOver"]]
    mix = {"none": "", "N": " with N"}[c["bMix"]]
    files["N.scala"] = (1, PKG + "trait N {\n  def n: V = V.mk\n  def q(v: V): Int = 1\n}\n")
    files["B.scala"] = (2, PKG + f"class B extends A{mix} {{\n{bo}}}\n")
    obj = {"none": "", "O": "object O extends B\n"}[c["oObj"]]
    if obj:
        files["O.scala"] = (2, PKG + obj)
    x = {
        "m": "  def f(a: A) = a.m\n",
        "pB": "  def f(b: B) = b.p(V.mk)\n",
        "mM": "  def f(a: M[V]) = a.m\n",
    }[c["xUse"]]
    files["X.scala"] = (2, PKG + f"object X {{\n{x}}}\n")
    return files


VALUECLASS = dict(
    factors=dict(
        vUnder=["Int", "String", "Long"],
        vKind=["anyval", "plain"],
        aGen=["none", "MV"],
        aRes=["V", "Int", "none"],
        aParam=["none", "V", "Vs"],
        bOver=["none", "m", "p"],
        xUse=["m", "pB", "mM"],
        bMix=["none", "N"],
        oObj=["none", "O"],
    ),
    render=valueclass,
)


# --- whole-class observation by a macro ------------------------------------------------------

MAC = PKG + """import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context

object Mac {
  /** The members of `T` declared in this package, with their types as seen from `T`. */
  def members[T]: String = macro impl[T]
  def impl[T: c.WeakTypeTag](c: Context): c.Tree = {
    import c.universe._
    val t = weakTypeOf[T]
    val ms = t.members.toList.filter(_.owner.fullName.startsWith("conf."))
      .map(s => s.name.decodedName.toString + ": " + s.typeSignatureIn(t).toString).sorted
    Literal(Constant(ms.mkString(", ")))
  }
}
"""


def macro(c):
    files = {"Mac.scala": (0, MAC)}
    files["M.scala"] = (1, PKG + "trait M[T] { def g: T = null.asInstanceOf[T] }\n")
    kw = "trait" if c["aKind"] == "trait" else "abstract class"
    par = {"none": "", "MInt": " extends M[Int]", "MString": " extends M[String]"}[c["aPar"]]
    am = {"none": "", "int": "  def a: Int = 1\n", "str": '  def a: String = ""\n',
          "priv": "  private def a: Int = 1\n"}[c["aMem"]]
    files["A.scala"] = (1, PKG + f"{kw} A{par} {{\n{am}}}\n")
    bm = {"none": "", "int": "  def b: Int = 1\n", "ovr": "  override def a: Int = 2\n"}[c["bMem"]]
    files["B.scala"] = (int(c["bTier"]), PKG + f"class B extends A {{\n{bm}}}\n")
    files["C.scala"] = (2, PKG + "class C extends B\n")
    w = {"C": "  val s = Mac.members[C]\n", "B": "  val s = Mac.members[B]\n",
         "A": "  val s = Mac.members[A]\n"}[c["wTarget"]]
    files["W.scala"] = (2, PKG + f"object W {{\n{w}}}\n")
    return files


MACRO = dict(
    factors=dict(
        aKind=["class", "trait"],
        aPar=["none", "MInt", "MString"],
        aMem=["none", "int", "str", "priv"],
        bMem=["none", "int", "ovr"],
        wTarget=["C", "B", "A"],
        bTier=["1", "2"],
    ),
    fixed=["bTier"],
    render=macro,
)


# --- overloads, added members and extension methods ------------------------------------------

def overloads(c):
    files = {}
    ao = {"none": "", "str": "  def m(x: String): Int = 2\n", "int": "  def m(x: Int): Int = 3\n",
          "long": "  def m(x: Long): Int = 4\n"}[c["aOver"]]
    ak = {"none": "", "k": "  def k: Int = 7\n", "kInt": "  def k(x: Int): Int = 8\n"}[c["aK"]]
    files["A.scala"] = (1, PKG + f"class A {{\n  def m: Int = 1\n{ao}{ak}}}\n")
    bo = {"none": "", "any": "  def m(x: Any): Int = 5\n", "int": "  def m(x: Int): Int = 6\n",
          "k": "  def k: Int = 9\n"}[c["bOver"]]
    files["B.scala"] = (2, PKG + f"class B extends A {{\n{bo}}}\n")
    ext = {"none": "", "k": "    def k: Int = 10\n", "mLong": "    def m(x: Long): Int = 11\n",
           "both": "    def k: Int = 10\n    def m(x: Long): Int = 11\n"}[c["ext"]]
    files["Ext.scala"] = (1, PKG + f"object Ext {{\n  implicit class R(a: A) {{\n{ext}    def z: Int = 0\n  }}\n}}\n")
    call = {"m1": "b.m(1)", "mS": 'b.m("s")', "mL": "b.m(1L)", "k": "b.k", "kA": "(b: A).k"}[c["xCall"]]
    files["X.scala"] = (2, PKG + f"import Ext._\nobject X {{\n  def f(b: B) = {call}\n}}\n")
    return files


OVERLOADS = dict(
    factors=dict(
        aOver=["none", "str", "int", "long"],
        aK=["none", "k", "kInt"],
        bOver=["none", "any", "int", "k"],
        ext=["none", "k", "mLong", "both"],
        xCall=["m1", "mS", "mL", "k", "kA"],
    ),
    render=overloads,
)


# --- companions and implicit scope -----------------------------------------------------------

def companions(c):
    files = {}
    files["Show.scala"] = (1, PKG + "trait Show[T] { def show: String }\nobject Show { def apply[T](s: String): Show[T] = new Show[T] { def show = s } }\n")
    ai = {"none": "", "gen": '  implicit def sa[T <: A]: Show[T] = Show("A")\n',
          "exact": '  implicit val sa: Show[A] = Show("A")\n',
          "forC": '  implicit def sc: Show[C] = Show("A for C")\n'}[c["aImp"]]
    am = {"none": "", "f": "  def f: Int = 1\n", "fStr": '  def f: String = ""\n'}[c["aObj"]]
    files["A.scala"] = (1, PKG + f"class A\nobject A {{\n{ai}{am}}}\n")
    bi = {"none": "", "gen": '  implicit def sb[T <: B]: Show[T] = Show("B")\n',
          "exact": '  implicit val sb: Show[B] = Show("B")\n',
          "forC": '  implicit def sbc: Show[C] = Show("B for C")\n'}[c["bImp"]]
    bobj = f"object B {{\n{bi}}}\n" if c["bImp"] != "none" or c["bObj"] == "yes" else ""
    up = 1 if c["cut"] == "X" else 2
    files["B.scala"] = (up, PKG + f"class B extends A\n{bobj}")
    ci = {"none": "", "exact": '  implicit val sc: Show[C] = Show("C")\n'}[c["cImp"]]
    cobj = f"object C {{\n{ci}}}\n" if c["cImp"] != "none" else ""
    files["C.scala"] = (up, PKG + f"class C extends B\n{cobj}")
    x = {"C": "  def s = implicitly[Show[C]].show\n", "B": "  def s = implicitly[Show[B]].show\n",
         "f": "  def s = A.f\n", "list": "  def s = implicitly[Show[C]]\n  def t = List(new C)\n"}[c["xUse"]]
    files["X.scala"] = (2, PKG + f"object X {{\n{x}}}\n")
    return files


COMPANIONS = dict(
    factors=dict(
        aImp=["none", "gen", "exact", "forC"],
        aObj=["none", "f", "fStr"],
        bImp=["none", "gen", "exact", "forC"],
        bObj=["no", "yes"],
        cImp=["none", "exact"],
        xUse=["C", "B", "f", "list"],
        cut=["X", "B"],
    ),
    fixed=["cut"],
    render=companions,
)


# --- sealed hierarchies and pattern matching -------------------------------------------------

def sealed(c):
    kw = {"trait": "sealed trait S", "class": "sealed abstract class S"}[c["sKind"]]
    s1 = "case class S1(i: Int) extends S\n"
    s2 = {"none": "", "obj": "case object S2 extends S\n", "cls": "case class S2(s: String) extends S\n"}[c["s2"]]
    s3 = {"none": "", "sealedSub": "sealed trait S3 extends S\ncase object S31 extends S3\n",
          "final": "final class S3 extends S\n"}[c["s3"]]
    files = {"S.scala": (1, PKG + f"{kw}\n{s1}{s2}{s3}")}
    cases = {"S1": "    case S1(i) => i\n",
             "S1S2": "    case S1(i) => i\n    case S2 => 2\n",
             "S1S2c": "    case S1(i) => i\n    case S2(_) => 2\n",
             "wild": "    case S1(i) => i\n    case _ => 0\n"}[c["xCases"]]
    files["X.scala"] = (2, PKG + f"object X {{\n  def f(s: S): Int = s match {{\n{cases}  }}\n}}\n")
    return files


SEALED = dict(
    factors=dict(
        sKind=["trait", "class"],
        s2=["none", "obj", "cls"],
        s3=["none", "sealedSub", "final"],
        xCases=["S1", "S1S2", "S1S2c", "wild"],
    ),
    render=sealed,
    scalacOptions="-Werror",
)


# --- Java in the hierarchy -------------------------------------------------------------------

def java(c):
    files = {}
    jm = {"int": "  public int m() { return 1; }\n", "str": '  public String m() { return ""; }\n',
          "abs": "  public abstract int m();\n", "none": "", "final": "  public final int m() { return 1; }\n"}[c["jM"]]
    jk = "final" if c["jFinal"] == "yes" else "abstract"
    files["J.java"] = (1, f"package conf;\n\npublic {jk} class J {{\n{jm}}}\n")
    bo = {"none": "", "ovr": "  override def m: Int = 2\n", "plain": "  def m: Int = 2\n"}[c["bOver"]]
    files["B.scala"] = (2, PKG + f"class B extends J {{\n{bo}}}\n")
    am = {"abs": "  def m: Int\n", "int": "  def m: Int = 1\n", "str": '  def m: String = ""\n'}[c["aM"]]
    files["A.scala"] = (1, PKG + f"abstract class A {{\n{am}}}\n")
    km = {"none": "", "int": "  public int m() { return 3; }\n"}[c["kM"]]
    files["K.java"] = (2, f"package conf;\n\npublic class K extends A {{\n{km}}}\n")
    x = {"B": "  def f(b: B) = b.m\n", "K": "  def f(k: K) = k.m\n", "J": "  def f(j: J) = j.m\n"}[c["xUse"]]
    files["X.scala"] = (2, PKG + f"object X {{\n{x}}}\n")
    return files


JAVA = dict(
    factors=dict(
        jM=["int", "str", "abs", "none", "final"],
        jFinal=["no", "yes"],
        bOver=["none", "ovr", "plain"],
        aM=["abs", "int", "str"],
        kM=["none", "int"],
        xUse=["B", "K", "J"],
    ),
    render=java,
)

SPACES = dict(fields=FIELDS, valueclass=VALUECLASS, macro=MACRO, overloads=OVERLOADS,
              companions=COMPANIONS, sealed=SEALED, java=JAVA)


def reversed_order(factors):
    names = list(factors)
    radices = [len(factors[n]) for n in names]
    total = 1
    for r in radices:
        total *= r
    for i in range(total):
        # the mixed-radix digits of i, first factor fastest
        digits, x = [], i
        for r in radices:
            digits.append(x % r)
            x //= r
        yield {n: factors[n][d] for n, d in zip(names, digits)}


def emit_space(name, max_bases):
    sp = SPACES[name]
    factors = sp["factors"]
    fixed = set(sp.get("fixed", []))
    for i, cfg in enumerate(itertools.islice(reversed_order(factors), max_bases)):
        files = sp["render"](cfg)
        edits = []
        for f, vals in factors.items():
            if f in fixed:
                continue
            for v in vals:
                if v == cfg[f]:
                    continue
                cfg2 = dict(cfg, **{f: v})
                files2 = sp["render"](cfg2)
                changed = {k: (s2 if s2 is not None else None)
                           for k in set(files) | set(files2)
                           for s2 in [files2.get(k, (0, None))[1]]
                           if files.get(k, (0, None))[1] != s2}
                if changed:
                    edits.append(dict(cls=f, cfg=" ".join(cfg2.values()), factors=cfg2, files=changed))
        out = dict(space=name, id=str(i), cfg=" ".join(cfg.values()), factors=cfg,
                   files={k: v[1] for k, v in files.items()},
                   tiers={k: v[0] for k, v in files.items()},
                   scalacOptions=sp.get("scalacOptions", ""), edits=edits)
        print(json.dumps(out))


GROUPINGS = {
    "own": {},
    "AM": {"A": "AM.scala", "M": "AM.scala"},
    "BC": {"B": "BC.scala", "C": "BC.scala"},
    "CXYZ": {c: "CXYZ.scala" for c in "CXYZ"},
}
GROUP_TIERS = {"AM.scala": 1, "BC.scala": 2, "CXYZ.scala": 2}


def emit_groups(path):
    """Each Lean base once per grouping of its classes into files (a fixed factor)."""
    for line in open(path):
        d = json.loads(line)
        for g, groups in GROUPINGS.items():
            e = dict(d, id=f"{d['id']}-{g}", groups=groups, tiers=GROUP_TIERS,
                     factors=dict(d["factors"], grouping=g), cfg=d["cfg"] + " " + g)
            e["edits"] = [dict(x, factors=dict(x["factors"], grouping=g)) for x in d["edits"]]
            print(json.dumps(e))


if __name__ == "__main__":
    args = sys.argv[1:]
    if args[0] == "--groups":
        emit_groups(args[1])
    else:
        n = int(args[args.index("--max-bases") + 1]) if "--max-bases" in args else 10 ** 9
        emit_space(args[0], n)
