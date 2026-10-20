package p;
public class J {
  public static void main(String[] args) {
    Object v = Top.N.m();
    if (!v.toString().equals(args[0])) throw new AssertionError(v);
  }
}
