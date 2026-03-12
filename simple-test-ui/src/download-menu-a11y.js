export const isMenuOpenKey = (key) =>
  key === "Enter" || key === " " || key === "ArrowDown" || key === "ArrowUp";

export const getNextMenuIndex = (currentIndex, key, itemCount) => {
  if (itemCount <= 0) {
    return -1;
  }

  const current = currentIndex < 0 ? 0 : currentIndex;
  if (key === "Home") return 0;
  if (key === "End") return itemCount - 1;
  if (key === "ArrowDown") return (current + 1) % itemCount;
  if (key === "ArrowUp") return (current - 1 + itemCount) % itemCount;
  return current;
};
