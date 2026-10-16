package p;
public class J {
  public static void main(String[] args) {
    Object v = O.m();
    if (!v.toString().equals(args[0])) throw new AssertionError(v);
  }
}
