// Command shop prints the total of a small order.
package main

import (
	"fmt"
	"os"

	"github.com/google/uuid"

	"example.com/playground/store"
)

const defaultCurrency = "EUR"

func main() {
	order := store.NewOrder(defaultCurrency)
	order.Add(store.Item{Name: "tea", Price: 350, Quantity: 2})
	order.Add(store.Item{Name: "cup", Price: 900, Quantity: 1})
	if len(os.Args) > 1 {
		fmt.Println("arguments:", os.Args[1:])
	}

	fmt.Printf("order %s, total: %d %s\n", uuid.NewString(), order.Total(), order.Currency)

	var priced store.Priced = store.Discounted{Order: order, Percent: 10}
	fmt.Printf("with a discount: %d %s\n", priced.Total(), order.Currency)
}
