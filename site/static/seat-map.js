// SeatWave seat map. This file is served as /static/seat-map.<hash>.js, cached for a year.
// It reads seat state from the API on every visit: the script is static, the seats are not.
document.addEventListener("DOMContentLoaded", async () => {
  const mapElement = document.getElementById("seat-map");
  const response = await fetch(mapElement.dataset.seats, { cache: "no-store" });
  const seatMap = await response.json();
  mapElement.textContent = seatMap.seats.length + " seats in section A";
});
