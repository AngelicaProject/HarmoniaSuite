//! Rust/libgit2 is the single Git implementation selected for Phase 4.
//!
//! The managed toolchain contains JDK and Node only. Git CLI archives are not
//! installed on either platform; repository operations will be built on this
//! repository type in the next phase.

pub type Repository = git2::Repository;
