package a;

public sealed interface S {}

sealed interface T extends S {}

final class A implements S {}

final class B implements T {}

final class C implements T {}
