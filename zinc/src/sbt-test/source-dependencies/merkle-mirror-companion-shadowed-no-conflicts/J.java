import java.lang.reflect.Modifier;
public class J {
  public static void main(String[] args) throws Exception {
    boolean fwd = Modifier.isStatic(C.class.getMethod("k").getModifiers());
    if (!String.valueOf(fwd).equals(args[0])) throw new AssertionError(fwd);
  }
}
