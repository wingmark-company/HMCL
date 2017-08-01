/*
 * Hello Minecraft! Launcher.
 * Copyright (C) 2017  huangyuhui <huanghongxun2008@126.com>
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see {http://www.gnu.org/licenses/}.
 */
package org.jackhuang.hmcl.game

import com.google.gson.JsonSyntaxException
import org.jackhuang.hmcl.util.GSON
import org.jackhuang.hmcl.util.LOG
import org.jackhuang.hmcl.util.fromJson
import org.jackhuang.hmcl.util.listFilesByExtension
import java.io.File
import java.io.IOException
import java.util.*
import java.util.logging.Level

open class DefaultGameRepository(val baseDirectory: File): GameRepository {
    protected val versions: MutableMap<String, Version> = TreeMap<String, Version>()

    override fun hasVersion(id: String) = versions.containsKey(id)
    override fun getVersion(id: String): Version {
        return versions[id] ?: throw VersionNotFoundException("Version '$id' does not exist.")
    }
    override fun getVersionCount() = versions.size
    override fun getVersions() = versions.values
    override fun getLibraryFile(id: Version, lib: Library) = File(baseDirectory, "libraries/${lib.path}")
    override fun getRunDirectory(id: String) = baseDirectory
    override fun getVersionJar(version: Version): File {
        val v = version.resolve(this)
        val id = v.id
        return getVersionRoot(id).resolve("$id.jar")
    }
    override fun getNativeDirectory(id: String) = File(getVersionRoot(id), "$id-natives")
    open fun getVersionRoot(id: String) = File(baseDirectory, "versions/$id")
    open fun getVersionJson(id: String) = File(getVersionRoot(id), "$id.json")
    open fun readVersionJson(id: String): Version? = readVersionJson(getVersionJson(id))
    @Throws(IOException::class, JsonSyntaxException::class, VersionNotFoundException::class)
    open fun readVersionJson(file: File): Version? = GSON.fromJson<Version>(file.readText())
    override fun renameVersion(from: String, to: String): Boolean {
        try {
            val fromVersion = getVersion(from)
            val fromDir = getVersionRoot(from)
            val toDir = getVersionRoot(to)
            if (!fromDir.renameTo(toDir))
                return false
            val toJson = File(toDir, "$to.json")
            val toJar = File(toDir, "$to.jar")
            if (!File(toDir, "$from.json").renameTo(toJson) ||
                    !File(toDir, "$from.jar").renameTo(toJar)) {
                toDir.renameTo(fromDir) // simple recovery
                return false
            }
            toJson.writeText(GSON.toJson(fromVersion.copy(id = to)))
            return true
        } catch (e: IOException) {
            return false
        } catch (e2: JsonSyntaxException) {
            return false
        } catch (e: VersionNotFoundException) {
            return false
        }
    }
    open fun removeVersionFromDisk(name: String): Boolean {
        val file = getVersionRoot(name)
        if (!file.exists())
            return true
        versions.remove(name)
        return file.deleteRecursively()
    }


    @Synchronized
    override fun refreshVersions() {
        versions.clear()

        if (ClassicVersion.hasClassicVersion(baseDirectory)) {
            val version = ClassicVersion()
            versions[version.id] = version
        }

        baseDirectory.resolve("versions").listFiles()?.filter { it.isDirectory }?.forEach tryVersion@{ dir ->
            val id = dir.name
            val json = dir.resolve("$id.json")

            // If user renamed the json file by mistake or created the json file in a wrong name,
            // we will find the only json and rename it to correct name.
            if (!json.exists()) {
                val jsons = dir.listFilesByExtension("json")
                if (jsons.size == 1)
                    jsons.single().renameTo(json)
            }

            var version: Version
            try {
                version = readVersionJson(json)!!
            } catch(e: Exception) { // JsonSyntaxException or IOException or NullPointerException(!!)
                // TODO: auto making up for the missing json
                // TODO: and even asking for removing the redundant version folder.
                return@tryVersion
            }

            if (id != version.id) {
                version = version.copy(id = id)
                try {
                    json.writeText(GSON.toJson(version))
                } catch(e: Exception) {
                    LOG.warning("Ignoring version $id because wrong id ${version.id} is set and cannot correct it.")
                    return@tryVersion
                }
            }

            versions[id] = version
        }
    }

    override fun getAssetIndex(assetId: String): AssetIndex {
        return GSON.fromJson(getIndexFile(assetId).readText())!!
    }

    override fun getActualAssetDirectory(assetId: String): File {
        try {
            return reconstructAssets(assetId)
        } catch (e: IOException) {
            LOG.log(Level.SEVERE, "Unable to reconstruct asset directory", e)
            return getAssetDirectory(assetId)
        }
    }

    override fun getAssetDirectory(assetId: String): File =
        baseDirectory.resolve("assets")

    @Throws(IOException::class)
    override fun getAssetObject(assetId: String, name: String): File {
        try {
            return getAssetObject(assetId, getAssetIndex(assetId).objects["name"]!!)
        } catch (e: Exception) {
            throw IOException("Asset index file malformed", e)
        }
    }

    override fun getAssetObject(assetId: String, obj: AssetObject): File =
        getAssetObject(getAssetDirectory(assetId), obj)

    open fun getAssetObject(assetDir: File, obj: AssetObject): File {
        return assetDir.resolve("objects/${obj.location}")
    }

    open fun getIndexFile(assetId: String): File =
        getAssetDirectory(assetId).resolve("indexes/$assetId.json")

    override fun getLoggingObject(assetId: String, loggingInfo: LoggingInfo): File =
        getAssetDirectory(assetId).resolve("log_configs/${loggingInfo.file.id}")

    @Throws(IOException::class, JsonSyntaxException::class)
    protected open fun reconstructAssets(assetId: String): File {
        val assetsDir = getAssetDirectory(assetId)
        val assetVersion = assetId
        val indexFile: File = getIndexFile(assetVersion)
        val virtualRoot = assetsDir.resolve("virtual").resolve(assetVersion)

        if (!indexFile.isFile) {
            return assetsDir
        }

        val assetIndexContent = indexFile.readText()
        val index = GSON.fromJson(assetIndexContent, AssetIndex::class.java) ?: return assetsDir

        if (index.virtual) {
            var cnt = 0
            LOG.info("Reconstructing virtual assets folder at " + virtualRoot)
            val tot = index.objects.entries.size
            for ((location, assetObject) in index.objects.entries) {
                val target = File(virtualRoot, location)
                val original = getAssetObject(assetsDir, assetObject)
                if (original.exists()) {
                    cnt++
                    if (!target.isFile)
                        original.copyTo(target)
                }
            }
            // If the scale new format existent file is lower then 0.1, use the old format.
            if (cnt * 10 < tot)
                return assetsDir
            else
                return virtualRoot
        }

        return assetsDir
    }
}