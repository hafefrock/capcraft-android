// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 CapCraft contributors

//! Each video's scrub copy, read while the playhead is dragged, and each
//! larger video's proxy, read while the picture plays: written in the
//! background into the project's cache, one at a time, from the moment the
//! video is added or the project opens. The playhead at rest and the export
//! read the files themselves. See `concat_host::scrub` and
//! `concat_host::proxy`.

use std::collections::HashSet;
use std::path::Path;
use std::sync::Arc;

use concat_host::session::Session;
use concat_project::model::MediaKind;

use crate::session::MobileSession;

/// One video a copy is made from: its path, size, the range it is read as
/// and whether it is HDR.
struct Video {
    path: String,
    width: u32,
    height: u32,
    range: Option<concat_media::ColorRange>,
    hdr: bool,
}

/// The project's videos, every one, by id.
fn videos(session: &Session) -> Vec<(String, Video)> {
    session
        .project()
        .media
        .iter()
        .filter(|item| item.kind == MediaKind::Video && !item.placeholder)
        .filter_map(|item| {
            Some((
                item.id.clone(),
                Video {
                    path: item.path.clone(),
                    width: item.width?,
                    height: item.height?,
                    range: item.color_range.map(concat_export::engine_range),
                    hdr: item.color_space.is_hdr(),
                },
            ))
        })
        .collect()
}

/// Queues the scrub copy, and the proxy where the video is large enough for
/// one, of every video not in `seen`, then adds it there. A copy already
/// written is adopted at once. The scrub copy is queued first, so it is
/// written ahead of the proxy.
pub(crate) fn see_to(session: &Session, seen: &mut HashSet<String>) {
    let project = Path::new(session.path());
    let all = videos(session);
    let in_project = session
        .project()
        .media
        .iter()
        .filter(|item| item.kind == MediaKind::Video && !item.placeholder)
        .count();
    if all.len() < in_project {
        log::info!(
            "copies: {} of the project's {in_project} videos have no size on record and get none",
            in_project - all.len()
        );
    }
    for (id, video) in all {
        if seen.insert(id) {
            concat_host::scrub::ensure(
                project,
                &video.path,
                video.width,
                video.height,
                video.range,
                video.hdr,
            );
            let wanted = concat_host::proxy::wanted(video.width, video.height);
            // on a phone a picture that plays reads the scrub copy, so a proxy is written only for the
            // video that has no scrub copy (an HDR one): one copy to wait for, not two
            let queued = (!cfg!(target_os = "android") || video.hdr)
                && concat_host::proxy::ensure(
                    project,
                    &video.path,
                    video.width,
                    video.height,
                    video.range,
                    video.hdr,
                );
            // what was decided, so a Share log says why a video has no proxy
            log::info!(
                "copies: {}: {}x{}, proxy {}",
                video.path,
                video.width,
                video.height,
                if queued {
                    "queued or adopted, read while it plays"
                } else if wanted && !video.hdr && cfg!(target_os = "android") {
                    "none: it plays from the scrub copy"
                } else if wanted {
                    "wanted, but the file cannot be read"
                } else {
                    "not needed (not over 1280x720)"
                }
            );
        }
    }
}

/// How far the proxies of a session's videos are: of those worth one, how
/// many are queued or being written, and how many there are.
#[derive(uniffi::Record)]
pub struct ProxyProgress {
    pub pending: u32,
    pub total: u32,
}

fn progress(session: &Session) -> ProxyProgress {
    let project = Path::new(session.path());
    let mut pending = 0;
    let mut total = 0;
    for (_, video) in videos(session) {
        if !concat_host::proxy::wanted(video.width, video.height) {
            continue;
        }
        total += 1;
        let waiting = if cfg!(target_os = "android") && !video.hdr {
            concat_host::scrub::pending(project, &video.path, video.range)
        } else {
            concat_host::proxy::pending(project, &video.path, video.range, video.hdr)
        };
        if waiting {
            pending += 1;
        }
    }
    ProxyProgress { pending, total }
}

/// The progress of `session`'s proxies, for the wait the editor shows while
/// they are written.
#[uniffi::export]
pub fn proxy_progress(session: Arc<MobileSession>) -> ProxyProgress {
    session
        .with_session(|session| Ok(progress(session)))
        .unwrap_or(ProxyProgress {
            pending: 0,
            total: 0,
        })
}

/// Holds the writing of scrub copies while a video is played straight from its
/// file (`on`), so the phone's video decoder is the player's alone; lets it go
/// on again when that stops.
#[uniffi::export]
pub fn hold_scrub_writes(on: bool) {
    concat_host::scrub::hold(on);
}

/// Removes the copies of media no longer in the project, or read as a
/// range they no longer are: nothing else clears them.
pub(crate) fn sweep(session: &Session) {
    let project = Path::new(session.path());
    let keep: HashSet<_> = videos(session)
        .iter()
        .filter_map(|(_, video)| concat_host::scrub::path_for(project, &video.path, video.range))
        .collect();
    let swept = concat_host::scrub::sweep(project, &keep);
    if swept > 0 {
        log::info!(
            "{swept} stale scrub copies swept from {}",
            project.display()
        );
    }
    let keep: HashSet<_> = videos(session)
        .iter()
        .filter_map(|(_, video)| {
            concat_host::proxy::path_for(project, &video.path, video.range, video.hdr)
        })
        .collect();
    let swept = concat_host::proxy::sweep(project, &keep);
    if swept > 0 {
        log::info!("{swept} stale proxies swept from {}", project.display());
    }
}
