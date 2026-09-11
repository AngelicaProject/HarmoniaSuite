use std::collections::BTreeSet;
use std::fs::{self, File, OpenOptions};
use std::io;
use std::path::{Component, Path, PathBuf};

use flate2::read::GzDecoder;
use thiserror::Error;
use zip::ZipArchive;

use crate::toolchain::ArchiveFormat;

#[derive(Debug, Error)]
pub enum ExtractionError {
    #[error("archive I/O failed: {0}")]
    Io(#[from] io::Error),
    #[error("archive format failed: {0}")]
    Archive(String),
    #[error("archive entry is unsafe: {0}")]
    UnsafeEntry(String),
    #[error("archive entry collides with another entry: {0}")]
    DuplicateEntry(PathBuf),
    #[error("archive path is unsafe: {0}")]
    UnsafePath(PathBuf),
}

pub fn extract_archive(
    archive_path: &Path,
    format: ArchiveFormat,
    destination: &Path,
) -> Result<(), ExtractionError> {
    reject_links(archive_path)?;
    if destination.exists() {
        return Err(ExtractionError::UnsafePath(destination.to_path_buf()));
    }
    ensure_existing_ancestors_are_safe(destination)?;
    fs::create_dir(destination)?;
    reject_links(destination)?;

    let result = match format {
        ArchiveFormat::Zip => extract_zip(archive_path, destination),
        ArchiveFormat::TarGz => extract_tar_gz(archive_path, destination),
    };
    if result.is_err() {
        let _ = fs::remove_dir_all(destination);
    }
    result
}

fn extract_zip(archive_path: &Path, destination: &Path) -> Result<(), ExtractionError> {
    let file = File::open(archive_path)?;
    let mut archive =
        ZipArchive::new(file).map_err(|error| ExtractionError::Archive(error.to_string()))?;
    let mut entries = BTreeSet::new();
    for index in 0..archive.len() {
        let mut entry = archive
            .by_index(index)
            .map_err(|error| ExtractionError::Archive(error.to_string()))?;
        let relative = safe_entry_path(entry.name())?;
        let output = destination.join(&relative);
        register_entry(&mut entries, &relative, &output)?;
        if entry.is_symlink() || is_zip_symlink(entry.unix_mode()) {
            return Err(ExtractionError::UnsafeEntry(entry.name().to_owned()));
        }
        if entry.is_dir() {
            create_directory(&output)?;
            continue;
        }
        ensure_parent(&output)?;
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&output)?;
        io::copy(&mut entry, &mut file)?;
        file.sync_all()?;
        set_executable_mode(&output, entry.unix_mode())?;
    }
    Ok(())
}

fn extract_tar_gz(archive_path: &Path, destination: &Path) -> Result<(), ExtractionError> {
    let file = File::open(archive_path)?;
    let decoder = GzDecoder::new(file);
    let mut archive = tar::Archive::new(decoder);
    let mut entries = BTreeSet::new();
    for item in archive
        .entries()
        .map_err(|error| ExtractionError::Archive(error.to_string()))?
    {
        let mut entry = item.map_err(|error| ExtractionError::Archive(error.to_string()))?;
        let raw_path = entry
            .path()
            .map_err(|error| ExtractionError::Archive(error.to_string()))?;
        let raw_path = raw_path.to_string_lossy().into_owned();
        let relative = safe_entry_path(&raw_path)?;
        let output = destination.join(&relative);
        register_entry(&mut entries, &relative, &output)?;
        let entry_type = entry.header().entry_type();
        if entry_type.is_symlink()
            || entry_type.is_hard_link()
            || entry_type.is_block_special()
            || entry_type.is_character_special()
            || entry_type.is_fifo()
        {
            return Err(ExtractionError::UnsafeEntry(raw_path));
        }
        if entry_type.is_dir() {
            create_directory(&output)?;
            continue;
        }
        if !entry_type.is_file() {
            return Err(ExtractionError::UnsafeEntry(raw_path));
        }
        ensure_parent(&output)?;
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&output)?;
        io::copy(&mut entry, &mut file)?;
        file.sync_all()?;
        set_executable_mode(&output, entry.header().mode().ok())?;
    }
    Ok(())
}

fn safe_entry_path(raw: &str) -> Result<PathBuf, ExtractionError> {
    let normalized = raw.replace('\\', "/");
    let path = Path::new(&normalized);
    let mut relative = PathBuf::new();
    for component in path.components() {
        match component {
            Component::Normal(value) => {
                let value = value.to_string_lossy();
                if value.contains(':') {
                    return Err(ExtractionError::UnsafeEntry(raw.to_owned()));
                }
                relative.push(value.as_ref());
            }
            Component::CurDir => {}
            Component::ParentDir | Component::RootDir | Component::Prefix(_) => {
                return Err(ExtractionError::UnsafeEntry(raw.to_owned()));
            }
        }
    }
    if relative.as_os_str().is_empty() {
        return Err(ExtractionError::UnsafeEntry(raw.to_owned()));
    }
    Ok(relative)
}

fn register_entry(
    entries: &mut BTreeSet<PathBuf>,
    relative: &Path,
    output: &Path,
) -> Result<(), ExtractionError> {
    if !entries.insert(relative.to_path_buf()) || output.exists() {
        return Err(ExtractionError::DuplicateEntry(relative.to_path_buf()));
    }
    Ok(())
}

fn create_directory(path: &Path) -> Result<(), ExtractionError> {
    ensure_parent(path)?;
    fs::create_dir(path)?;
    reject_links(path)?;
    Ok(())
}

fn ensure_parent(path: &Path) -> Result<(), ExtractionError> {
    let parent = path
        .parent()
        .ok_or_else(|| ExtractionError::UnsafePath(path.to_path_buf()))?;
    fs::create_dir_all(parent)?;
    ensure_existing_ancestors_are_safe(parent)?;
    Ok(())
}

fn ensure_existing_ancestors_are_safe(path: &Path) -> Result<(), ExtractionError> {
    for ancestor in path.ancestors().collect::<Vec<_>>().into_iter().rev() {
        match fs::symlink_metadata(ancestor) {
            Ok(_) => reject_links(ancestor)?,
            Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
            Err(error) => return Err(error.into()),
        }
    }
    Ok(())
}

fn reject_links(path: &Path) -> Result<(), ExtractionError> {
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink() || is_reparse_point(path)? {
        return Err(ExtractionError::UnsafePath(path.to_path_buf()));
    }
    Ok(())
}

fn is_zip_symlink(mode: Option<u32>) -> bool {
    mode.map(|mode| mode & 0o170000 == 0o120000)
        .unwrap_or(false)
}

#[cfg(unix)]
fn set_executable_mode(path: &Path, mode: Option<u32>) -> Result<(), ExtractionError> {
    use std::os::unix::fs::PermissionsExt;
    if let Some(mode) = mode {
        if mode & 0o777 != 0 {
            fs::set_permissions(path, fs::Permissions::from_mode(mode & 0o777))?;
        }
    }
    Ok(())
}

#[cfg(not(unix))]
fn set_executable_mode(_path: &Path, _mode: Option<u32>) -> Result<(), ExtractionError> {
    Ok(())
}

#[cfg(not(windows))]
fn is_reparse_point(_path: &Path) -> io::Result<bool> {
    Ok(false)
}

#[cfg(windows)]
fn is_reparse_point(path: &Path) -> io::Result<bool> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Storage::FileSystem::{
        GetFileAttributesW, FILE_ATTRIBUTE_REPARSE_POINT, INVALID_FILE_ATTRIBUTES,
    };
    let wide: Vec<u16> = path
        .as_os_str()
        .encode_wide()
        .chain(std::iter::once(0))
        .collect();
    let attributes = unsafe { GetFileAttributesW(wide.as_ptr()) };
    if attributes == INVALID_FILE_ATTRIBUTES {
        return Err(io::Error::last_os_error());
    }
    Ok(attributes & FILE_ATTRIBUTE_REPARSE_POINT != 0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::write::GzEncoder;
    use flate2::Compression;
    use tar::{Builder, EntryType, Header};
    use tempfile::tempdir;

    #[test]
    fn rejects_parent_traversal_and_removes_partial_destination() {
        let root = tempdir().unwrap();
        let archive = root.path().join("malicious.tar.gz");
        write_tar(&archive, |builder| {
            let mut header = Header::new_gnu();
            header.set_size(4);
            header.set_mode(0o644);
            header.set_cksum();
            builder.append_data(&mut header, "../escape", &b"keep"[..])
        });
        let destination = root.path().join("staging");
        assert!(matches!(
            extract_archive(&archive, ArchiveFormat::TarGz, &destination),
            Err(ExtractionError::UnsafeEntry(_))
        ));
        assert!(!destination.exists());
        assert!(!root.path().join("escape").exists());
    }

    #[test]
    fn rejects_symlink_archive_entries() {
        let root = tempdir().unwrap();
        let archive = root.path().join("symlink.tar.gz");
        write_tar(&archive, |builder| {
            let mut header = Header::new_gnu();
            header.set_entry_type(EntryType::symlink());
            header.set_size(0);
            header.set_link_name("../../outside").unwrap();
            header.set_cksum();
            builder.append_data(&mut header, "link", &[][..])
        });
        let destination = root.path().join("staging");
        assert!(matches!(
            extract_archive(&archive, ArchiveFormat::TarGz, &destination),
            Err(ExtractionError::UnsafeEntry(_))
        ));
        assert!(!destination.exists());
    }

    fn write_tar<F>(path: &Path, append: F)
    where
        F: FnOnce(&mut Builder<GzEncoder<File>>) -> io::Result<()>,
    {
        let file = File::create(path).unwrap();
        let encoder = GzEncoder::new(file, Compression::fast());
        let mut builder = Builder::new(encoder);
        append(&mut builder).unwrap();
        let encoder = builder.into_inner().unwrap();
        encoder.finish().unwrap();
    }
}
