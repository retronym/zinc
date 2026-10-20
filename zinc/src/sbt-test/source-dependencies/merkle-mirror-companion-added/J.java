public class J {
  public static void main(String[] args) throws Exception {
    Object v = O.class.getMethod(args[0]).invoke(null);
    if (!v.toString().equals(args[1])) throw new AssertionError(v);
  }
}
