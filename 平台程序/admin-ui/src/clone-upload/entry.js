import './clear-legacy'

if (location.hash.startsWith('#replay=')) {
  import('./replay.js')
} else {
  import('./index.js')
}
