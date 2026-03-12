export const resolveScrollTop = ({ previousScrollTop, scrollHeight, scrollToBottom }) =>
  scrollToBottom ? scrollHeight : previousScrollTop;
