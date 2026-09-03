import { expect, test } from '@playwright/test'

test('loads the terminal and enters the craft flow', async ({ page }) => {
  await page.goto('/')

  await expect(page.getByText('AE2 Gateway')).toBeVisible()
  await expect(page.getByText('MOCK')).toBeVisible()
  await expect(page.getByText('Orbital Fabrication')).toBeVisible()

  await page.getByPlaceholder('Search…').fill('Fluix Crystal')
  const tile = page.getByRole('button', { name: /Fluix Crystal/ })
  await expect(tile).toBeEnabled()
  await tile.click()

  await expect(page.getByRole('dialog')).toContainText('Craft Fluix Crystal')
  await page.getByRole('button', { name: 'Calculate plan' }).click()
  await expect(page.getByRole('button', { name: 'Start' })).toBeVisible()
  await expect(page.getByText('Available:')).toBeVisible()
})
