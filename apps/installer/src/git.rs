//! libgit2-backed source mirror and detached checkout primitives.
//!
//! Git is deliberately not a managed executable. The Phase 4 pipeline uses this module for
//! every mirror, fetch, commit-resolution and checkout operation on Windows and Linux.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use git2::{build::CheckoutBuilder, build::RepoBuilder, FetchOptions, Oid, Repository};
use reqwest::Url;
use serde::{Deserialize, Serialize};
use thiserror::Error;

const ORIGIN_MAIN_REF: &str = "refs/remotes/origin/main";

#[derive(Debug, Error)]
pub enum GitError {
    #[error("libgit2 operation failed: {0}")]
    Git(#[from] git2::Error),
    #[error("managed source I/O failed: {0}")]
    Io(#[from] io::Error),
    #[error("source remote URL must be an HTTPS URL with a host")]
    InvalidRemoteUrl,
    #[error("managed source remote origin does not match configured URL")]
    RemoteMismatch,
    #[error("managed source ref origin/main is missing")]
    MissingOriginMain,
    #[error("invalid commit SHA: {0}")]
    InvalidCommit(String),
    #[error("unsafe managed checkout path: {0}")]
    UnsafePath(PathBuf),
    #[error("managed checkout is not owned by Harmonia for commit {0}")]
    NotOwned(String),
    #[error("managed checkout marker is invalid: {0}")]
    InvalidMarker(PathBuf),
    #[error("managed checkout marker JSON failed: {0}")]
    Json(#[from] serde_json::Error),
    #[error("failed to clean partial managed checkout {path}: {reason}")]
    CleanupFailed { path: PathBuf, reason: String },
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ResolvedCommit {
    pub sha: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ManagedCheckout {
    pub path: PathBuf,
    pub target_sha: String,
    marker: PathBuf,
}

impl ManagedCheckout {
    pub fn marker_path(&self) -> &Path {
        &self.marker
    }
}

#[derive(Clone, Debug)]
pub struct ManagedGitRepository {
    source_dir: PathBuf,
    remote_url: String,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
struct CheckoutMarker {
    schema_version: u32,
    target_commit: String,
}

impl ManagedGitRepository {
    pub fn new(
        source_dir: impl Into<PathBuf>,
        remote_url: impl Into<String>,
    ) -> Result<Self, GitError> {
        let remote_url = remote_url.into();
        validate_remote_url(&remote_url, cfg!(test))?;
        Ok(Self {
            source_dir: source_dir.into(),
            remote_url,
        })
    }

    #[cfg(test)]
    pub(crate) fn new_for_test(
        source_dir: impl Into<PathBuf>,
        remote_url: impl Into<String>,
    ) -> Result<Self, GitError> {
        let remote_url = remote_url.into();
        validate_remote_url(&remote_url, true)?;
        Ok(Self {
            source_dir: source_dir.into(),
            remote_url,
        })
    }

    pub fn source_dir(&self) -> &Path {
        &self.source_dir
    }

    pub fn fetch_origin_main(&self) -> Result<ResolvedCommit, GitError> {
        let repository = self.open_or_initialize_source()?;
        ensure_origin(&repository, &self.remote_url)?;
        let mut remote = repository.find_remote("origin")?;
        let mut fetch_options = FetchOptions::new();
        remote.fetch(
            &["+refs/heads/main:refs/remotes/origin/main"],
            Some(&mut fetch_options),
            None,
        )?;
        resolve_main(&repository)
    }

    /// Fetch one already-selected object without consulting the moving main ref.  The caller
    /// must have obtained the SHA from a trusted source (for example a verified manifest).
    pub fn fetch_exact_commit(&self, target_sha: &str) -> Result<ResolvedCommit, GitError> {
        validate_commit_sha(target_sha)?;
        let repository = self.open_or_initialize_source()?;
        ensure_origin(&repository, &self.remote_url)?;
        let mut remote = repository.find_remote("origin")?;
        let mut fetch_options = FetchOptions::new();
        remote.fetch(&[target_sha], Some(&mut fetch_options), None)?;
        let target = Oid::from_str(target_sha)
            .map_err(|_| GitError::InvalidCommit(target_sha.to_owned()))?;
        repository.find_commit(target)?;
        Ok(ResolvedCommit {
            sha: target_sha.to_owned(),
        })
    }

    pub fn resolve_main(&self) -> Result<ResolvedCommit, GitError> {
        let repository = Repository::open_bare(&self.source_dir)?;
        resolve_main(&repository)
    }

    pub fn verify_commit(&self, target_sha: &str) -> Result<(), GitError> {
        validate_commit_sha(target_sha)?;
        let repository = Repository::open_bare(&self.source_dir)?;
        let target = Oid::from_str(target_sha)
            .map_err(|_| GitError::InvalidCommit(target_sha.to_owned()))?;
        repository.find_commit(target)?;
        Ok(())
    }

    pub fn prepare_checkout(
        &self,
        target_sha: &str,
        build_root: &Path,
    ) -> Result<ManagedCheckout, GitError> {
        validate_commit_sha(target_sha)?;
        validate_managed_path(build_root, build_root)?;
        fs::create_dir_all(build_root)?;
        validate_managed_path(build_root, build_root)?;
        let destination = build_root.join(target_sha);
        let marker = marker_path(build_root, target_sha);
        validate_managed_path(build_root, &destination)?;
        validate_managed_path(build_root, &marker)?;

        if destination.exists() {
            self.cleanup_existing_checkout(&destination, &marker, target_sha)?;
        } else if marker.exists() {
            validate_tree(&marker)?;
            fs::remove_file(&marker)?;
        }

        let result = (|| -> Result<ManagedCheckout, GitError> {
            let repository = Repository::open_bare(&self.source_dir)?;
            let target = Oid::from_str(target_sha)
                .map_err(|_| GitError::InvalidCommit(target_sha.into()))?;
            repository.find_commit(target)?;
            repository.set_head(ORIGIN_MAIN_REF)?;

            let source = self.source_dir.to_string_lossy().into_owned();
            let checkout_repository = RepoBuilder::new().clone(&source, &destination)?;
            checkout_repository.set_head_detached(target)?;
            let mut checkout_options = CheckoutBuilder::new();
            checkout_options.force();
            checkout_repository.checkout_head(Some(&mut checkout_options))?;
            verify_checkout_head(&checkout_repository, target_sha)?;

            let marker_value = CheckoutMarker {
                schema_version: 1,
                target_commit: target_sha.to_owned(),
            };
            fs::write(&marker, serde_json::to_vec_pretty(&marker_value)?)?;
            Ok(ManagedCheckout {
                path: destination.clone(),
                target_sha: target_sha.to_owned(),
                marker: marker.clone(),
            })
        })();
        match result {
            Ok(checkout) => Ok(checkout),
            Err(error) => {
                cleanup_partial_checkout(&destination, &marker).map_err(|cleanup_error| {
                    GitError::CleanupFailed {
                        path: destination.clone(),
                        reason: format!("{error}; cleanup failed: {cleanup_error}"),
                    }
                })?;
                Err(error)
            }
        }
    }

    pub fn cleanup_checkout(&self, checkout: &ManagedCheckout) -> Result<(), GitError> {
        validate_commit_sha(&checkout.target_sha)?;
        let build_root = checkout
            .marker
            .parent()
            .ok_or_else(|| GitError::UnsafePath(checkout.marker.clone()))?;
        let expected_marker = marker_path(build_root, &checkout.target_sha);
        if checkout.marker != expected_marker {
            return Err(GitError::UnsafePath(checkout.marker.clone()));
        }
        self.cleanup_existing_checkout(&checkout.path, &checkout.marker, &checkout.target_sha)
    }

    fn open_or_initialize_source(&self) -> Result<Repository, GitError> {
        validate_managed_path(&self.source_dir, &self.source_dir)?;
        if self.source_dir.exists() {
            let is_empty =
                self.source_dir.is_dir() && fs::read_dir(&self.source_dir)?.next().is_none();
            if is_empty {
                return Ok(Repository::init_bare(&self.source_dir)?);
            }
            return Ok(Repository::open_bare(&self.source_dir)?);
        }
        if let Some(parent) = self.source_dir.parent() {
            fs::create_dir_all(parent)?;
        }
        Ok(Repository::init_bare(&self.source_dir)?)
    }

    fn cleanup_existing_checkout(
        &self,
        destination: &Path,
        marker: &Path,
        target_sha: &str,
    ) -> Result<(), GitError> {
        if !marker.is_file() {
            return Err(GitError::NotOwned(target_sha.to_owned()));
        }
        let value: CheckoutMarker = serde_json::from_slice(&fs::read(marker)?)
            .map_err(|_| GitError::InvalidMarker(marker.to_path_buf()))?;
        if value.schema_version != 1 || value.target_commit != target_sha {
            return Err(GitError::NotOwned(target_sha.to_owned()));
        }
        validate_managed_path(
            marker
                .parent()
                .ok_or_else(|| GitError::UnsafePath(marker.to_path_buf()))?,
            destination,
        )?;
        if destination.exists() {
            validate_tree(destination)?;
            fs::remove_dir_all(destination)?;
        }
        validate_tree(marker)?;
        fs::remove_file(marker)?;
        Ok(())
    }
}

fn ensure_origin(repository: &Repository, remote_url: &str) -> Result<(), GitError> {
    match repository.find_remote("origin") {
        Ok(remote) => {
            if remote.url() != Some(remote_url) {
                return Err(GitError::RemoteMismatch);
            }
        }
        Err(error) if error.code() == git2::ErrorCode::NotFound => {
            repository.remote("origin", remote_url)?;
        }
        Err(error) => return Err(error.into()),
    }
    Ok(())
}

fn resolve_main(repository: &Repository) -> Result<ResolvedCommit, GitError> {
    let reference = repository
        .find_reference(ORIGIN_MAIN_REF)
        .map_err(|_| GitError::MissingOriginMain)?;
    let commit = reference.peel_to_commit()?;
    Ok(ResolvedCommit {
        sha: commit.id().to_string(),
    })
}

fn verify_checkout_head(repository: &Repository, target_sha: &str) -> Result<(), GitError> {
    let head = repository
        .head()?
        .target()
        .ok_or(GitError::MissingOriginMain)?;
    if head.to_string() != target_sha {
        return Err(GitError::InvalidCommit(format!(
            "checkout HEAD {} does not match target {}",
            head, target_sha
        )));
    }
    Ok(())
}

fn validate_remote_url(value: &str, allow_test_file_url: bool) -> Result<(), GitError> {
    let url = Url::parse(value).map_err(|_| GitError::InvalidRemoteUrl)?;
    if !url.username().is_empty() || url.password().is_some() {
        return Err(GitError::InvalidRemoteUrl);
    }
    let valid = (url.scheme() == "https" && url.host_str().is_some())
        || (allow_test_file_url && url.scheme() == "file");
    if valid {
        Ok(())
    } else {
        Err(GitError::InvalidRemoteUrl)
    }
}

fn validate_commit_sha(value: &str) -> Result<(), GitError> {
    if value.len() == 40 && value.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        Ok(())
    } else {
        Err(GitError::InvalidCommit(value.to_owned()))
    }
}

fn marker_path(build_root: &Path, target_sha: &str) -> PathBuf {
    build_root.join(format!(".{target_sha}.harmonia-worktree.json"))
}

fn cleanup_partial_checkout(destination: &Path, marker: &Path) -> Result<(), io::Error> {
    if destination.exists() {
        validate_managed_path(
            destination.parent().ok_or_else(|| {
                io::Error::new(io::ErrorKind::InvalidInput, "checkout has no parent")
            })?,
            destination,
        )
        .map_err(|error| io::Error::new(io::ErrorKind::PermissionDenied, error.to_string()))?;
        validate_tree(destination)
            .map_err(|error| io::Error::new(io::ErrorKind::PermissionDenied, error.to_string()))?;
        fs::remove_dir_all(destination)?;
    }
    if marker.exists() {
        validate_tree(marker)
            .map_err(|error| io::Error::new(io::ErrorKind::PermissionDenied, error.to_string()))?;
        fs::remove_file(marker)?;
    }
    Ok(())
}

fn validate_managed_path(root: &Path, path: &Path) -> Result<(), GitError> {
    if !root.is_absolute() || !path.is_absolute() || !path.starts_with(root) {
        return Err(GitError::UnsafePath(path.to_path_buf()));
    }
    for ancestor in path.ancestors().collect::<Vec<_>>().into_iter().rev() {
        match fs::symlink_metadata(ancestor) {
            Ok(metadata) if metadata.file_type().is_symlink() || is_reparse_point(ancestor)? => {
                return Err(GitError::UnsafePath(ancestor.to_path_buf()))
            }
            Ok(_) => {}
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }
    }
    Ok(())
}

fn validate_tree(path: &Path) -> Result<(), GitError> {
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink() || is_reparse_point(path)? {
        return Err(GitError::UnsafePath(path.to_path_buf()));
    }
    if metadata.is_dir() {
        for entry in fs::read_dir(path)? {
            validate_tree(&entry?.path())?;
        }
    }
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
    use tempfile::tempdir;

    fn commit_in_remote(repository: &Repository, contents: &[u8]) -> String {
        let blob = repository.blob(contents).unwrap();
        let mut tree_builder = repository.treebuilder(None).unwrap();
        tree_builder.insert("README.md", blob, 0o100644).unwrap();
        let tree = repository.find_tree(tree_builder.write().unwrap()).unwrap();
        let signature = git2::Signature::now("Harmonia Test", "test@example.invalid").unwrap();
        let parent = repository
            .find_reference("refs/heads/main")
            .ok()
            .and_then(|reference| reference.target())
            .and_then(|oid| repository.find_commit(oid).ok());
        let parents = parent.iter().collect::<Vec<_>>();
        repository
            .commit(
                Some("refs/heads/main"),
                &signature,
                &signature,
                "test commit",
                &tree,
                &parents,
            )
            .unwrap()
            .to_string()
    }

    #[test]
    fn fetches_and_resolves_exact_origin_main_sha() {
        let root = tempdir().unwrap();
        let remote_path = root.path().join("remote.git");
        let remote = Repository::init_bare(&remote_path).unwrap();
        let expected = commit_in_remote(&remote, b"exact");
        let source = ManagedGitRepository::new_for_test(
            root.path().join("app/source"),
            Url::from_file_path(&remote_path).unwrap().to_string(),
        )
        .unwrap();

        let resolved = source.fetch_origin_main().unwrap();
        assert_eq!(resolved.sha, expected);
        assert_eq!(source.resolve_main().unwrap().sha, expected);
    }

    #[test]
    fn rejects_credentials_in_https_remote_urls() {
        assert!(matches!(
            validate_remote_url("https://user@example.invalid/repo.git", false),
            Err(GitError::InvalidRemoteUrl)
        ));
        assert!(matches!(
            validate_remote_url("https://user:secret@example.invalid/repo.git", false),
            Err(GitError::InvalidRemoteUrl)
        ));
    }

    #[test]
    fn force_updated_main_is_followed_by_the_fetch_refspec() {
        let root = tempdir().unwrap();
        let remote_path = root.path().join("remote.git");
        let remote = Repository::init_bare(&remote_path).unwrap();
        let first = commit_in_remote(&remote, b"first");
        let source = ManagedGitRepository::new_for_test(
            root.path().join("app/source"),
            Url::from_file_path(&remote_path).unwrap().to_string(),
        )
        .unwrap();

        assert_eq!(source.fetch_origin_main().unwrap().sha, first);
        let second = commit_in_remote(&remote, b"second");
        assert_eq!(source.fetch_origin_main().unwrap().sha, second);
        assert_ne!(first, second);
    }

    #[test]
    fn exact_fetch_does_not_re_resolve_moving_main() {
        let root = tempdir().unwrap();
        let remote_path = root.path().join("remote.git");
        let remote = Repository::init_bare(&remote_path).unwrap();
        let first = commit_in_remote(&remote, b"first");
        let source = ManagedGitRepository::new_for_test(
            root.path().join("app/source"),
            Url::from_file_path(&remote_path).unwrap().to_string(),
        )
        .unwrap();
        source.fetch_origin_main().unwrap();
        let second = commit_in_remote(&remote, b"second");
        assert_eq!(source.fetch_exact_commit(&first).unwrap().sha, first);
        assert_eq!(source.resolve_main().unwrap().sha, second);
        source.verify_commit(&first).unwrap();
    }

    #[test]
    fn rebuild_of_same_sha_replaces_dirty_owned_checkout() {
        let root = tempdir().unwrap();
        let remote_path = root.path().join("remote.git");
        let remote = Repository::init_bare(&remote_path).unwrap();
        let expected = commit_in_remote(&remote, b"clean");
        let source = ManagedGitRepository::new_for_test(
            root.path().join("app/source"),
            Url::from_file_path(&remote_path).unwrap().to_string(),
        )
        .unwrap();
        source.fetch_origin_main().unwrap();

        let build_root = root.path().join("app/build");
        let checkout = source.prepare_checkout(&expected, &build_root).unwrap();
        fs::write(checkout.path.join("contamination.txt"), b"dirty").unwrap();
        let rebuilt = source.prepare_checkout(&expected, &build_root).unwrap();

        assert_eq!(rebuilt.target_sha, expected);
        assert!(!rebuilt.path.join("contamination.txt").exists());
        assert_eq!(
            Repository::open(&rebuilt.path)
                .unwrap()
                .head()
                .unwrap()
                .target(),
            Some(Oid::from_str(&expected).unwrap())
        );
    }

    #[test]
    fn refuses_to_remove_unmarked_checkout() {
        let root = tempdir().unwrap();
        let remote_path = root.path().join("remote.git");
        let remote = Repository::init_bare(&remote_path).unwrap();
        let expected = commit_in_remote(&remote, b"owned");
        let source = ManagedGitRepository::new_for_test(
            root.path().join("app/source"),
            Url::from_file_path(&remote_path).unwrap().to_string(),
        )
        .unwrap();
        source.fetch_origin_main().unwrap();

        let build_root = root.path().join("app/build");
        let destination = build_root.join(&expected);
        fs::create_dir_all(&destination).unwrap();
        fs::write(destination.join("user-file"), b"do not touch").unwrap();
        assert!(matches!(
            source.prepare_checkout(&expected, &build_root),
            Err(GitError::NotOwned(_))
        ));
        assert!(destination.join("user-file").exists());
    }

    #[cfg(unix)]
    #[test]
    fn rejects_symlink_ancestor_above_managed_build_root() {
        use std::os::unix::fs::symlink;

        let root = tempdir().unwrap();
        let remote_path = root.path().join("remote.git");
        let remote = Repository::init_bare(&remote_path).unwrap();
        let expected = commit_in_remote(&remote, b"symlink ancestor");
        let source = ManagedGitRepository::new_for_test(
            root.path().join("app/source"),
            Url::from_file_path(&remote_path).unwrap().to_string(),
        )
        .unwrap();
        source.fetch_origin_main().unwrap();

        let outside = root.path().join("outside");
        fs::create_dir_all(&outside).unwrap();
        let managed_link = root.path().join("managed-link");
        symlink(&outside, &managed_link).unwrap();
        let build_root = managed_link.join("build");
        assert!(matches!(
            source.prepare_checkout(&expected, &build_root),
            Err(GitError::UnsafePath(_))
        ));
        assert!(fs::read_dir(&outside).unwrap().next().is_none());
    }

    #[cfg(unix)]
    #[test]
    fn rejects_symlink_ancestor_above_managed_source_dir() {
        use std::os::unix::fs::symlink;

        let root = tempdir().unwrap();
        let remote_path = root.path().join("remote.git");
        let remote = Repository::init_bare(&remote_path).unwrap();
        commit_in_remote(&remote, b"source symlink ancestor");
        let outside = root.path().join("outside");
        fs::create_dir_all(&outside).unwrap();
        let managed_link = root.path().join("managed-link");
        symlink(&outside, &managed_link).unwrap();
        let source = ManagedGitRepository::new_for_test(
            managed_link.join("source"),
            Url::from_file_path(&remote_path).unwrap().to_string(),
        )
        .unwrap();

        assert!(matches!(
            source.fetch_origin_main(),
            Err(GitError::UnsafePath(_))
        ));
        assert!(fs::read_dir(&outside).unwrap().next().is_none());
    }

    #[test]
    #[ignore = "network smoke test exercised explicitly by CI"]
    fn fetches_public_https_origin_main_with_libgit2() {
        let root = tempdir().unwrap();
        let source = ManagedGitRepository::new(
            root.path().join("source"),
            "https://github.com/AngelicaProject/HarmoniaSuite.git",
        )
        .unwrap();
        let resolved = source.fetch_origin_main().unwrap();
        assert_eq!(resolved.sha.len(), 40);
        assert!(resolved.sha.bytes().all(|byte| byte.is_ascii_hexdigit()));
    }
}
