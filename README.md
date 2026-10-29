# maghraby-editor

A lightweight, zero-dependency, modal terminal text editor built entirely from scratch in pure Java. Designed for speed and minimal memory footprint, featuring native ANSI terminal rendering, a multi-threaded engine, multi-language syntax highlighting, an integrated code execution runner, an interactive file tree sidebar, and a highly optimized **Piece Table** core architecture.

---

## Key Architectural Highlights

- **Piece Table Data Structure (v2.0 Engine):** The core buffer engine utilizes a Piece Table architecture (`originalBuffer`, `addBuffer`, and `pieces`). This guarantees near $O(1)$ time complexity for text insertions and deletions, making the editor blazing fast and highly memory-efficient even when handling massive files.
- **$O(1)$ Lazy Line Indexing (`delta` / `deltaFrom` Shift):** Line index updates during continuous typing are optimized from $O(N)$ to $O(1)$ time complexity. By maintaining a pending positional `delta` shift across line start entries, typing inside a multi-million-line file updates offset calculations in constant time without re-indexing all subsequent lines.
- **Zero-Copy Streaming File Saver:** Document saving streams `Piece` chunks directly to a `BufferedWriter` without concatenating the full document into intermediate memory `String` buffers, keeping heap allocations flat during large file writes.
- **Zero-Allocation Autocomplete Engine:** Word suggestions use `String.regionMatches` over a localized search window ($\pm 150$ lines around the cursor) rather than instantiating substring objects, drastically reducing Garbage Collector pressure during fast typing.
- **Signal-Driven Terminal Resizing (`SIGWINCH`):** Handles UNIX `SIGWINCH` signals via native JVM hooks for instant, zero-lag UI re-layouts on terminal resize, supplemented by a low-frequency fallback polling daemon.
- **Zero External Dependencies:** Built with pure Java and direct POSIX terminal control via ANSI escape codes and `stty`. No third-party UI libraries or framework abstractions.
- **Multi-Threaded Architecture:**
  - **Input Thread:** Non-blocking raw byte parsing and escape sequence decoding (CSI/SS3 sequences).
  - **Render Thread:** Smooth, decoupled rendering loop (~60 FPS) with horizontal/vertical viewport clipping and per-line syntax highlighter caching.
  - **Resize Watcher Daemon:** Background terminal size detection paired with signal handlers for dynamic relayout.
- **Memory-Efficient Delta Undo/Redo:**
  - Uses an action-based delta model (`EditAction`) storing only individual microscopic edits instead of heavy full-buffer snapshots.
  - Bounded custom LIFO stack (up to 1,500 actions) preventing memory leaks during long coding sessions.
- **Safe Code Runner (`:run` / `:compile`):**
  - Compiles and runs **Java**, **C**, **C++**, and **Python**.
  - Uses isolated temporary build directories to keep user workspaces clean of `.class` or binary artifacts.
  - Employs signal guard protection (`SigintGuard`) via Java reflection to prevent `Ctrl+C` from closing the editor while running user programs.

---

## Core Features & UI Components

### 1. Interactive File Tree Sidebar
- Toggleable file tree viewer mapped to `Ctrl+B` or `:tree` / `:explorer`.
- Navigate directories using arrow keys, open files with `Enter`, or switch focus seamlessly between panes and the sidebar using `Tab` or `Ctrl+W`.

### 2. In-Editor File Navigation
- Open new files or switch active buffers without leaving the editor using `:e <filename>` or `:edit <filename>`.
- Force-reload files or discard unsaved changes with `:e!`.

### 3. Visual Progress Status Bar
- Dual-color progress indicator integrated into the status bar (`╱╱╱`) that visually reflects the cursor's relative vertical position in the active document.

---

## Editor Controls & Keybindings

### 1. Normal Mode (Default)
| Key | Action |
| --- | --- |
| `i` | Enter **Insert Mode** |
| `:` | Enter **Command Mode** |
| `Ctrl + B` | Toggle **File Tree Sidebar** |
| `Tab` / `Ctrl + W` | Cycle focus between editor panes and sidebar |
| `u` | **Undo** last change |
| `r` | **Redo** reverted change |
| `F5` | Run / Compile current file |
| `Ctrl + S` | Quick save |
| `Ctrl + Q` | Force quit |
| `Arrow Keys` / `Home` / `End` / `PgUp` / `PgDn` | Cursor navigation |
| `q` | Safe quit (warns if unsaved) |

### 2. Insert Mode
| Key | Action |
| --- | --- |
| `Typing` | In-place character insertion |
| `Tab` | Accept highlighted autocomplete suggestion / 4-space indent |
| `↑` / `↓` | Navigate autocomplete suggestions list |
| `Backspace` | In-place character deletion before cursor |
| `Delete` | Forward character deletion |
| `Enter` | New line insertion |
| `Esc` | Return to **Normal Mode** |

### 3. File Tree Sidebar Mode
| Key | Action |
| --- | --- |
| `↑` / `↓` | Move selection up / down |
| `Enter` | Open selected file / Expand directory |
| `Tab` / `Ctrl + W` | Return focus to text editor pane |
| `Esc` | Blur sidebar focus |

### 4. Command Mode (`:`)
| Command | Usage | Description |
| --- | --- | --- |
| `:e <file>` / `:edit <file>` | `:e main.java` | Open a file in the active pane |
| `:e!` / `:edit!` | `:e!` | Reload current file or discard unsaved changes |
| `:tree` / `:explorer` | `:tree` | Toggle or open the file tree at a directory |
| `:w [filename]` | `:w` or `:w app.java` | Save buffer to current or new file |
| `:wq` / `:x` | `:wq` | Save and quit |
| `:q` | `:q` | Quit (checks for unsaved changes) |
| `:q!` | `:q!` | Force quit discarding changes |
| `:goto <line>` / `:g <line>` | `:g 42` or `:g $` | Move cursor to specific line number (`$` for end) |
| `:run [args]` | `:run arg1 arg2` | Compile and run active file with terminal handover |
| `:compile` / `:build` | `:compile` | Compile / syntax check without running |
| `:help` / `:h` | `:help` | Show command assistance |

---

## Supported Compilers & Runtimes

- **Java:** `javac` & `java` (automatic package declaration detection and source-root calculation)
- **C:** `gcc`, `clang`, or `cc` (auto-links `-lm`)
- **C++:** `g++`, `clang++`, or `c++`
- **Python:** `python3`, `python`, or `py` (in-memory syntax validation)

---

## Requirements

- **OS:** Linux, macOS, WSL, or Git-Bash (requires a POSIX terminal supporting `stty` and ANSI escapes)
- **Java:** JDK 17+
- **Build Tool:** Apache Maven

---

## Build & Run
> **Note:** If you prefer not to build the project manually, you can grab the latest pre-compiled JAR from the [Releases](https://github.com/mohammed1elmaghraby/maghraby-editor/releases) page.

Clone the repository:
```bash
git clone https://github.com/mohammed1elmaghraby/maghraby-editor.git
cd maghraby-editor
```

Build the JAR:
```bash
mvn clean package
```

Run directly:
```bash
java -jar target/maghraby-editor-3.0.jar [filename]
```

Build with ❤️ by Mohammed Elmaghraby.