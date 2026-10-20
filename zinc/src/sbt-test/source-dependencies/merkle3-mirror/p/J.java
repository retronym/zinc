package p;

public class J {
  public static Object f() { return O.m(); }
  public static void main(String[] args) {
    if (!f().toString().equals(args[0])) throw new AssertionError(f());
  }
}
