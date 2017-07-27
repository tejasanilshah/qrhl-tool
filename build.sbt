name := "tool"

version := "1.0"

scalaVersion := "2.12.2"

enablePlugins(LibisabellePlugin)

libraryDependencies += "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.6"
libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.3" % "test"

isabelleVersions := Seq("2016-1")
isabelleSessions in Compile := Seq("QRHL")
//isabelleSources := Seq(baseDirectory.value / "src/main/isabelle/.libisabelle")

//unmanagedResourceDirectories in Compile += baseDirectory.value / "src/main/isabelle"

libraryDependencies ++= Seq(
  "info.hupel" %% "libisabelle" % "0.8.3",
  "info.hupel" %% "libisabelle-setup" % "0.8.3",
  "info.hupel" %% "pide-package" % "0.8.3"
)

// https://mvnrepository.com/artifact/org.slf4j/slf4j-simple
libraryDependencies += "org.slf4j" % "slf4j-simple" % "1.7.25" % "test"
libraryDependencies += "org.jline" % "jline" % "3.3.0"



//import sbtassembly.AssemblyPlugin.defaultShellScript
//assemblyOption in assembly := (assemblyOption in assembly).value.copy(prependShellScript = Some(defaultShellScript))
mainClass in assembly := Some("qrhl.toplevel.Toplevel")
//assemblyJarName in assembly := "qrhl.jar"
assemblyOutputPath in assembly := baseDirectory.value / "qrhl.jar"
test in assembly := {}
