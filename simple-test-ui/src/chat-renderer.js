import { getNextMenuIndex, isMenuOpenKey } from "./download-menu-a11y.js";
import { resolveScrollTop } from "./chat-scroll.js";

const ICON_DOWNLOAD_ACTION = `
  <svg class="icon" viewBox="0 0 24 24" aria-hidden="true">
    <path d="M12 3v12"></path>
    <path d="M7 10l5 5 5-5"></path>
    <path d="M4 21h16"></path>
  </svg>
`;

const focusDownloadOption = (popover, index) => {
  const options = Array.from(popover.querySelectorAll(".download-option"));
  if (options.length === 0) return;
  const clampedIndex = Math.max(0, Math.min(index, options.length - 1));
  options.forEach((node, nodeIndex) => {
    node.tabIndex = nodeIndex === clampedIndex ? 0 : -1;
  });
  options[clampedIndex].focus();
};

export const createChatRenderer = ({
  messagesElement,
  onCopy,
  onToggleDownloadMenu,
  onSelectDownload
}) => {
  const positionDownloadPopovers = () => {
    const viewportPadding = 8;
    const popovers = messagesElement.querySelectorAll(".download-menu .download-popover");

    popovers.forEach((popover) => {
      popover.classList.remove("is-below");
      popover.style.transform = "translateX(0)";

      let rect = popover.getBoundingClientRect();
      let deltaX = 0;

      if (rect.right > window.innerWidth - viewportPadding) {
        deltaX -= rect.right - (window.innerWidth - viewportPadding);
      }
      if (rect.left + deltaX < viewportPadding) {
        deltaX += viewportPadding - (rect.left + deltaX);
      }
      if (deltaX !== 0) {
        popover.style.transform = `translateX(${Math.round(deltaX)}px)`;
      }

      rect = popover.getBoundingClientRect();
      if (rect.top < viewportPadding) {
        popover.classList.add("is-below");
      }
    });
  };

  const render = (messages, { scrollToBottom = false } = {}) => {
    const previousScrollTop = messagesElement.scrollTop;
    messagesElement.innerHTML = "";

    messages.forEach((msg, msgIndex) => {
      const bubble = document.createElement("div");
      bubble.className = `message ${msg.role}`;
      bubble.dataset.messageId = String(msg.id ?? msgIndex);

      const content = msg.displayedContent ?? msg.content;
      bubble.textContent = content;
      if (msg.isNew) {
        bubble.classList.add("is-new");
        msg.isNew = false;
      }

      if (msg.meta) {
        const metaEl = document.createElement("div");
        metaEl.className = "meta";
        metaEl.textContent = msg.meta;
        bubble.appendChild(metaEl);
      }

      if (msg.badges) {
        const badgeEl = document.createElement("div");
        badgeEl.className = "meta";
        badgeEl.textContent = msg.badges;
        bubble.appendChild(badgeEl);
      }

      if (msg.role === "assistant" && msg.typingDone && (msg.downloads || msg.copy)) {
        const actions = document.createElement("div");
        actions.className = "actions";

        if (msg.copy) {
          const copyBtn = document.createElement("button");
          copyBtn.className = `btn action-btn copy-btn${msg.copied ? " is-copied" : ""}`;
          copyBtn.type = "button";
          copyBtn.textContent = msg.copied ? "Copied" : "Copy";
          copyBtn.setAttribute("aria-label", "Copy output text");
          copyBtn.addEventListener("click", () => onCopy(msg));
          actions.appendChild(copyBtn);
        }

        const formats = Array.isArray(msg.downloadFormats) && msg.downloadFormats.length > 0
          ? msg.downloadFormats
          : [];

        if (msg.downloads && formats.length > 0) {
          const menuWrap = document.createElement("div");
          menuWrap.className = "download-menu";

          const menuId = `download-menu-${msg.id ?? msgIndex}`;
          const launcher = document.createElement("button");
          launcher.className = "btn action-btn download-launcher";
          launcher.innerHTML = ICON_DOWNLOAD_ACTION;
          launcher.type = "button";
          launcher.title = "Download";
          launcher.setAttribute("aria-label", "Download options");
          launcher.setAttribute("aria-haspopup", "menu");
          launcher.setAttribute("aria-expanded", String(Boolean(msg.downloadMenuOpen)));
          launcher.setAttribute("aria-controls", menuId);
          launcher.addEventListener("click", (event) => {
            event.stopPropagation();
            onToggleDownloadMenu(msg, !msg.downloadMenuOpen, { focusFirst: false });
          });
          launcher.addEventListener("keydown", (event) => {
            if (isMenuOpenKey(event.key)) {
              event.preventDefault();
              onToggleDownloadMenu(msg, true, { focusFirst: true });
              return;
            }
            if (event.key === "Escape" && msg.downloadMenuOpen) {
              event.preventDefault();
              onToggleDownloadMenu(msg, false, { focusFirst: false });
            }
          });
          menuWrap.appendChild(launcher);

          if (msg.downloadMenuOpen) {
            const popover = document.createElement("div");
            popover.id = menuId;
            popover.className = "download-popover";
            popover.setAttribute("role", "menu");
            popover.addEventListener("click", (event) => event.stopPropagation());

            formats.forEach((format, index) => {
              const opt = document.createElement("button");
              opt.className = "download-option";
              opt.type = "button";
              opt.setAttribute("role", "menuitem");
              opt.tabIndex = index === 0 ? 0 : -1;
              opt.textContent = `Download ${format}`;

              opt.addEventListener("click", (event) => {
                event.stopPropagation();
                onSelectDownload(msg, format);
              });

              opt.addEventListener("keydown", (event) => {
                if (event.key === "Escape") {
                  event.preventDefault();
                  onToggleDownloadMenu(msg, false, { focusFirst: false });
                  requestAnimationFrame(() => launcher.focus());
                  return;
                }

                const nextIndex = getNextMenuIndex(index, event.key, formats.length);
                if (nextIndex !== index) {
                  event.preventDefault();
                  focusDownloadOption(popover, nextIndex);
                  return;
                }

                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  onSelectDownload(msg, format);
                }
              });

              popover.appendChild(opt);
            });

            if (msg.focusMenuOnOpen) {
              requestAnimationFrame(() => {
                focusDownloadOption(popover, 0);
                msg.focusMenuOnOpen = false;
              });
            }

            menuWrap.appendChild(popover);
          }

          actions.appendChild(menuWrap);
        }

        bubble.appendChild(actions);
      }

      messagesElement.appendChild(bubble);
    });

    messagesElement.scrollTop = resolveScrollTop({
      previousScrollTop,
      scrollHeight: messagesElement.scrollHeight,
      scrollToBottom
    });
    requestAnimationFrame(positionDownloadPopovers);
  };

  return { render, positionDownloadPopovers };
};
