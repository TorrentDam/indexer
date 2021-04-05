object Index extends App {

  val content = os
    .walk(paths.metadataPath)
    .filter(os.isFile)
    .map { path =>
      val bytes = os.read.bytes(path)
      upickle.default.read[common.Metadata](bytes)
    }

  val json = upickle.default.write(content, indent = 2)

  os.write.over(paths.indexPath, json, createFolders = true)

  val readmePath = os.pwd / "README.md"

  os.write.over(
    readmePath,
    os.read(readmePath)
      .replaceFirst(
        "/badge/indexed-\\d+-blue",
        s"/badge/indexed-${content.size}-blue"
      )
  )
}