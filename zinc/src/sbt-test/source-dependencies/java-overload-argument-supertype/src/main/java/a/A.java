package a;
public class A {
  public static void foo(Object o) { throw new RuntimeException("foo(Object)"); }
  public static void foo(Number n) {}
}
