package app;
public class J {
  public static void main(String[] args) {
    Object v = lib.B.s();
    if (!v.toString().equals(args[0])) throw new AssertionError(v);
  }
}
